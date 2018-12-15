package org.hifly.quickstarts.bank.account.aggregator;

import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.hifly.quickstarts.bank.account.command.CreateAccountCommand;
import org.hifly.quickstarts.bank.account.command.DepositAmountCommand;
import org.hifly.quickstarts.bank.account.command.WithdrawalAmountCommand;
import org.hifly.quickstarts.bank.account.event.*;
import org.hifly.quickstarts.bank.account.model.Account;
import org.hifly.quickstarts.bank.account.queryManager.AccountInMemoryView;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;

public class AccountAggregate {

    @AggregateIdentifier
    private String accountId;

    public AccountAggregate() { }

    @CommandHandler
    public AccountAggregate(CreateAccountCommand command) {
        apply(new AccountCreatedEvent(command.getAccountId(), command.getCustomerName()));
    }

    @EventHandler
    public void on(AccountCreatedEvent event) {
        this.accountId = event.getAccountId();
    }


    @CommandHandler
    public void deposit(DepositAmountCommand command) {
        apply(new AmountDepositEvent(command.getAccountId(), command.getAmount()));
    }

    @CommandHandler
    public void withdrawal(WithdrawalAmountCommand command) {
        Account account = AccountInMemoryView.accounts.get(command.getAccountId());
        if(account == null) {
            apply(new AccountNotExistingEvent(command.getAccountId(), null));
            return;
        }
        if(account.getBalance() < command.getAmount())
            apply(new AmountWithdrawalDeniedEvent(command.getAccountId(), command.getAmount(), account.getBalance()));
        else
            apply(new AmountWithdrawalEvent(command.getAccountId(), command.getAmount()));
    }

}
