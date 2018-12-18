package org.hifly.axon.bank.account;

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.common.stream.BlockingStream;
import org.axonframework.config.Configurer;
import org.axonframework.config.DefaultConfigurer;
import org.axonframework.eventhandling.*;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.extensions.kafka.eventhandling.consumer.KafkaMessageSource;
import org.axonframework.extensions.kafka.eventhandling.producer.KafkaPublisher;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.modelling.command.AggregateAnnotationCommandHandler;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.hifly.axon.bank.account.aggregator.AccountAggregate;
import org.hifly.axon.bank.account.command.CreateAccountCommand;
import org.hifly.axon.bank.account.command.DepositAmountCommand;
import org.hifly.axon.bank.account.command.WithdrawalAmountCommand;
import org.hifly.axon.bank.account.config.AxonKafkaConfig;
import org.hifly.axon.bank.account.config.AxonUtil;
import org.hifly.axon.bank.account.event.AccountClosedEvent;
import org.hifly.axon.bank.account.handler.AccountEventHandler;
import org.hifly.axon.bank.account.queryManager.QueryController;
import org.hifly.axon.bank.account.saga.CloseAccountSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.axonframework.eventhandling.GenericEventMessage.asEventMessage;
import static org.axonframework.eventhandling.Segment.ROOT_SEGMENT;

public class BankAccountApp {

    private static final Logger LOG = LoggerFactory.getLogger(BankAccountApp.class);

    private static List<Future<Integer>> futures = new ArrayList<>();

    public static void main(String[] args) {

        //event bus and event store
        EventBus eventBus = SimpleEventBus.builder().build();
        EventStore eventStore = EmbeddedEventStore.builder().storageEngine(new InMemoryEventStorageEngine()).build();
        final AnnotationEventHandlerAdapter annotationEventListenerAdapter = AxonUtil.createAnnotationEventHandler(new AccountEventHandler());
        eventStore.subscribe(messages -> messages.forEach(e -> {
                    try {
                        annotationEventListenerAdapter.handle(e);
                    } catch (Exception e1) {
                        throw new RuntimeException(e1);
                    }
                }
        ));

        //kafka
        KafkaPublisher<String, byte[]> kafkaPublisher = AxonKafkaConfig.createPublisher(eventBus, "axon");
        KafkaPublisher<String, byte[]> kafkaEventStorePublisher = AxonKafkaConfig.createPublisher(eventStore, "axon");

        //aggregate commands
        CommandBus commandBus = SimpleCommandBus.builder().build();
        CommandGateway commandGateway = DefaultCommandGateway.builder().commandBus(commandBus).build();
        EventSourcingRepository<AccountAggregate> repository = AxonUtil.createEventSourceRepository(eventStore, AccountAggregate.class);
        AggregateAnnotationCommandHandler<AccountAggregate> aggregatorHandler = AxonUtil.createAggregatorHandler(repository, AccountAggregate.class);
        aggregatorHandler.subscribe(commandBus);

        //saga
        AnnotatedSagaRepository<CloseAccountSaga> sagaRepository = AxonUtil.createAnnotatedSagaRepository(CloseAccountSaga.class);
        Supplier<CloseAccountSaga> accountSagaSupplier = () -> new CloseAccountSaga(eventBus);
        AnnotatedSagaManager<CloseAccountSaga> sagaManager = AxonUtil.createAnnotatedSagaManager(accountSagaSupplier, sagaRepository, CloseAccountSaga.class);
        eventBus.subscribe(messages -> messages.forEach(e -> {
                    try {
                        if (sagaManager.canHandle(e, null))
                            sagaManager.handle(e, ROOT_SEGMENT);
                    } catch (Exception e1) {
                        throw new RuntimeException(e1);
                    }
                }
        ));


        Configurer configurer = DefaultConfigurer.defaultConfiguration();
        configurer
                .eventProcessing(eventProcessingConfigurer -> eventProcessingConfigurer
                        .registerEventHandler(configuration -> new AccountEventHandler()))
                .eventProcessing(eventProcessingConfigurer -> eventProcessingConfigurer
                        .registerSaga(CloseAccountSaga.class, sc -> sc.configureSagaStore(c -> new InMemorySagaStore())))
                .registerCommandHandler(configuration -> aggregatorHandler)
                .configureEventBus(configuration -> eventBus)
                .configureCommandBus(configuration -> commandBus)
                .configureEventStore(configuration -> eventStore)
                .buildConfiguration();


        runSimulation(eventBus, commandGateway, kafkaPublisher, kafkaEventStorePublisher);


    }

    private static void runSimulation(
            EventBus eventBus,
            CommandGateway commandGateway,
            KafkaPublisher<String, byte[]> kafkaPublisher,
            KafkaPublisher<String, byte[]> kafkaEventStorePublisher) {

        LOG.info("----->>>Starting simulation<<<-----");

        kafkaEventStorePublisher.start();
        kafkaPublisher.start();

        sendCommands(commandGateway);
        queries();
        receiveExternalEvents(eventBus);


    }

    private static void sendCommands(CommandGateway commandGateway) {
        final String itemId = "A1";
        commandGateway.send(new CreateAccountCommand(itemId, "kermit the frog"));
        final String itemId2 = "A2";
        commandGateway.send(new CreateAccountCommand(itemId2, "john the law"));

        ExecutorService executorService1 = scheduleDeposit(commandGateway, itemId);
        ExecutorService executorService2 = scheduleWithdrawal(commandGateway, itemId);
        ExecutorService executorService3 = scheduleDeposit(commandGateway, itemId2);
        ExecutorService executorService4 = scheduleWithdrawal(commandGateway, itemId2);


        for (Future<Integer> fut : futures) {
            try {
                fut.get();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        executorService1.shutdown();
        executorService2.shutdown();
        executorService3.shutdown();
        executorService4.shutdown();
    }

    private static ExecutorService scheduleDeposit(CommandGateway commandGateway, String itemId) {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        Double upper = 10000.0;
        Double lower = 1.0;

        for (int i = 0; i < 2; i++) {
            Future<Integer> future = executor.submit(() -> {
                        commandGateway.send(
                                new DepositAmountCommand(itemId,
                                        Math.floor((Math.random() * (upper - lower) + lower) * 100) / 100));
                        return 1;
                    }
            );
            futures.add(future);
        }
        return executor;
    }

    private static ExecutorService scheduleWithdrawal(CommandGateway commandGateway, String itemId) {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        Double upper = 10000.0;
        Double lower = 1.0;


        for (int i = 0; i < 2; i++) {
            Future<Integer> future = executor.submit(() -> {
                        commandGateway.send(
                                new WithdrawalAmountCommand(itemId,
                                        Math.floor((Math.random() * (upper - lower) + lower) * 100) / 100));
                        return 1;
                    }
            );
            futures.add(future);
        }
        return executor;
    }

    private static void queries() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        QueryController queryController = new QueryController();

        executor.submit(() -> {
                    while (true) {
                        queryController.printAccountsDetail();
                        Thread.sleep(10000);
                    }
                }
        );
    }

    private static void receiveExternalEvents(EventBus eventBus) {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        KafkaMessageSource kafkaMessageSource = AxonKafkaConfig.createMessageSource("axon");
        BlockingStream<TrackedEventMessage<?>> stream = kafkaMessageSource.openStream(null);

        LOG.info("----->>>Starting receiveExternalEvents<<<-----");

        executor.submit(() -> {
            while (true) {
                while (stream.hasNextAvailable(10, TimeUnit.SECONDS)) {
                    TrackedEventMessage<?> actual = stream.nextAvailable();
                    if (actual.getPayload() instanceof AccountClosedEvent) {
                        AccountClosedEvent accountClosedEvent = (AccountClosedEvent) actual.getPayload();
                        LOG.info("----->>>received AccountClosedEvent, account:" +  accountClosedEvent.getAccountId() + "<<<-----");
                        EventMessage<AccountClosedEvent> message = asEventMessage(accountClosedEvent);
                        UnitOfWork<Message<AccountClosedEvent>> uow = DefaultUnitOfWork.startAndGet(message);
                        try {
                            eventBus.publish(message);
                            uow.commit();
                        } catch (Exception e) {
                            uow.rollback(e);
                        }
                    }
                }
                Thread.sleep(10000);
            }
        });


    }
}
