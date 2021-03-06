package org.hifly.axon.bank.account.handler;

import org.axonframework.eventhandling.EventHandler;
import org.hifly.axon.bank.account.event.*;
import org.hifly.axon.bank.account.model.Account;
import org.hifly.axon.bank.account.queryManager.AccountInMemoryView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class AccountEventHandler {

    private final Logger LOG = LoggerFactory.getLogger(AccountEventHandler.class);

    @EventHandler
    public void handle(AccountCreatedEvent event) {
        AccountInMemoryView.accounts.put(event.getAccountId(), new Account(event.getAccountId(), event.getCustomerName()));
        LOG.info("account created {}, customer {} ", event.getAccountId(), event.getCustomerName());
    }

    @EventHandler
    public void handle(AmountDepositEvent event) {
        Account account = AccountInMemoryView.accounts.get(event.getAccountId());
        account.setBalance(account.getBalance() + event.getAmount());
        AccountInMemoryView.accounts.put(event.getAccountId(), account);
        LOG.info("account {}, deposit {} ", event.getAccountId(), event.getAmount());
    }

    @EventHandler
    public void handle(AmountWithdrawalEvent event) {
        Account account = AccountInMemoryView.accounts.get(event.getAccountId());
        account.setBalance(account.getBalance() - event.getAmount());
        LOG.info("account {}, withdrawal {} ", event.getAccountId(), event.getAmount());
    }

    @EventHandler
    public void handle(AmountWithdrawalDeniedEvent event) {
        LOG.info("account {}, withdrawal {} denied!!! - current balance {}", event.getAccountId(), event.getAmount(), event.getCurrentBalance());
    }

    @EventHandler
    public void handle(AccountNotExistingEvent event) {
        LOG.info("account {} not existing", event.getAccountId());
    }


}