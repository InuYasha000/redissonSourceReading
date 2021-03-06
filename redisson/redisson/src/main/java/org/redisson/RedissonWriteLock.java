/**
 * Copyright (c) 2013-2020 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.pubsub.LockPubSub;

/**
 * Lock will be removed automatically if client disconnects.
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonWriteLock extends RedissonLock implements RLock {

    protected RedissonWriteLock(CommandAsyncExecutor commandExecutor, String name) {
        super(commandExecutor, name);
    }

    @Override
    String getChannelName() {
        return prefixName("redisson_rwlock", getName());
    }

    @Override
    protected String getLockName(long threadId) {
        return super.getLockName(threadId) + ":write";
    }

    /**
     * 加写锁
     */
    @Override
    <T> RFuture<T> tryLockInnerAsync(long waitTime, long leaseTime, TimeUnit unit, long threadId, RedisStrictCommand<T> command) {
        internalLockLeaseTime = unit.toMillis(leaseTime);

        //anyLock: {
        //  “mode”: “read”,
        //  “UUID_01:threadId_01”: 1,
        //  “UUID_02:threadId_02”: 1
        //}
        //{anyLock}:UUID_01:threadId_01:rwlock_timeout:1		1
        //{anyLock}:UUID_02:threadId_02:rwlock_timeout:1		1
        return evalWriteAsync(getName(), LongCodec.INSTANCE, command,
                            "local mode = redis.call('hget', KEYS[1], 'mode'); " +           //hget anyLock mode
                            "if (mode == false) then " +
                                  "redis.call('hset', KEYS[1], 'mode', 'write'); " +                //hset anyLock mode write
                                  "redis.call('hset', KEYS[1], ARGV[2], 1); " +                     //hset anyLock UUID_01:threadId_01:write 1
                                  "redis.call('pexpire', KEYS[1], ARGV[1]); " +                     //pexpire anyLock 30000
                                  "return nil; " +
                              "end; " +
                              "if (mode == 'write') then " +
                                  "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +       //先读后写，在这里是直接不支持的，即使是同一个客户端同一个线程，加写锁之前只能没有锁
                                      "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
                                      "local currentExpire = redis.call('pttl', KEYS[1]); " +
                                      "redis.call('pexpire', KEYS[1], currentExpire + ARGV[1]); " + //这里把过期时间延长了
                                      "return nil; " +
                                  "end; " +
                                "end;" +
                                "return redis.call('pttl', KEYS[1]);",
                        Arrays.<Object>asList(getName()),              //KEYS:anyLock
                        internalLockLeaseTime, getLockName(threadId)); //ARGV:30s,UUID_01:threadId_01:write
    }

    @Override
    protected RFuture<Boolean> unlockInnerAsync(long threadId) {
        //anyLock: {
        //  “mode”: “write”,
        //  “UUID_01:threadId_01:write”: 2,
        //  “UUID_01:threadId_01”: 1
        //}
        //
        //{anyLock}:UUID_01:threadId_01:rwlock_timeout:2		1
        return evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "local mode = redis.call('hget', KEYS[1], 'mode'); " +
                "if (mode == false) then " +
                    "redis.call('publish', KEYS[2], ARGV[1]); " +
                    "return 1; " +
                "end;" +
                "if (mode == 'write') then " +
                    "local lockExists = redis.call('hexists', KEYS[1], ARGV[3]); " +
                    "if (lockExists == 0) then " +
                        "return nil;" +
                    "else " +
                        "local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1); " + //可重入减1
                        "if (counter > 0) then " +
                            "redis.call('pexpire', KEYS[1], ARGV[2]); " +                 //减1后还有客户端持有锁，更新生存时间30s，直接返回
                            "return 0; " +
                        "else " +
                            "redis.call('hdel', KEYS[1], ARGV[3]); " +                    //没有客户端持有锁了  hdel anyLock UUID_01:threadId_01:write
                            "if (redis.call('hlen', KEYS[1]) == 1) then " +               //检查 anyLock 还有几个元素
                                "redis.call('del', KEYS[1]); " +                          //这里的一个是mode
                                "redis.call('publish', KEYS[2], ARGV[1]); " + 
                            "else " +
                                // has unlocked read-locks
                                "redis.call('hset', KEYS[1], 'mode', 'read'); " +          //此时写锁退化成读锁
                            "end; " +
                            "return 1; "+
                        "end; " +
                    "end; " +
                "end; "
                + "return nil;",
        Arrays.<Object>asList(getName(), getChannelName()),                                //KEYS: anyLock , redisson_rwlock:{anyLock}
        LockPubSub.READ_UNLOCK_MESSAGE, internalLockLeaseTime, getLockName(threadId));//ARGV: 0, 30s , UUID_01:threadId_01:write
    }
    
    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

    @Override
    public RFuture<Boolean> forceUnlockAsync() {
        cancelExpirationRenewal(null);
        return evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
              "if (redis.call('hget', KEYS[1], 'mode') == 'write') then " +
                  "redis.call('del', KEYS[1]); " +
                  "redis.call('publish', KEYS[2], ARGV[1]); " +
                  "return 1; " +
              "end; " +
              "return 0; ",
              Arrays.<Object>asList(getName(), getChannelName()), LockPubSub.READ_UNLOCK_MESSAGE);
    }

    @Override
    public boolean isLocked() {
        RFuture<String> future = commandExecutor.writeAsync(getName(), StringCodec.INSTANCE, RedisCommands.HGET, getName(), "mode");
        String res = get(future);
        return "write".equals(res);
    }

}
