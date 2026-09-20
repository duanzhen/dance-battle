package org.dromara.common.core.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后再执行;当前没有事务时立即执行。
 *
 * <p>用于把 SSE 推送这类「慢且可丢」的副作用挪出写事务:推送要往网络写数据,
 * 大屏/裁判端客户端一卡住,发送就会阻塞——在事务里推送等于把数据库写锁一直攥在手里
 * (单机 SQLite 只有单写者,别的请求就只能干等甚至报 database is locked)。</p>
 *
 * <p>顺带修掉一个语义问题:事务回滚时不会再广播"改好了",前端不会拿着一个并不存在
 * 的变更去刷新。</p>
 *
 * @author duane
 */
public final class AfterCommitUtils {

    private AfterCommitUtils() {
    }

    /**
     * 注册「提交后执行」;无事务时直接执行。
     *
     * @param task 提交后要执行的动作(可为 null,忽略)
     */
    public static void runAfterCommit(Runnable task) {
        if (task == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }
}
