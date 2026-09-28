package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户注册表与账户状态变更入口。
 * <p>并发模型：账户对象自身是把锁（{@link Account#lock()}）。
 * 同一账户的资金/持仓检查与变更在锁内一次做完（check-then-act 原子化），
 * 因此并发下单不可能把冻结扣成负数；不同账户之间完全并行。
 */
@Service
public class AccountService {

    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();

    public void register(Account account) {
        accounts.put(account.getId(), account);
    }

    public Account get(long accountId) {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new TradingException(RejectCode.ACCOUNT_NOT_FOUND, "账户不存在: accountId=" + accountId);
        }
        return a;
    }

    public Collection<Account> all() {
        return accounts.values();
    }

    /**
     * 在账户锁内执行动作并返回结果，保证同一账户的所有资金/持仓变更串行化。
     */
    public <T> T withAccount(long accountId, java.util.function.Function<Account, T> action) {
        Account account = get(accountId);
        synchronized (account.lock()) {
            return action.apply(account);
        }
    }
}
