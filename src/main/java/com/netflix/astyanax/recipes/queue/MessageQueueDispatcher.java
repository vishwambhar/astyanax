package com.netflix.astyanax.recipes.queue;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.netflix.astyanax.recipes.locks.BusyLockException;

/**
 * The message queue dispatcher reads message from the message queue
 * and dispatches to worker threads.
 * 
 * @author elandau
 *
 */
public class MessageQueueDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(MessageQueueDispatcher.class);
    
    public final static int   DEFAULT_BATCH_SIZE            = 5;
    public final static int   DEFAULT_POLLING_INTERVAL      = 1; // Seconds
    public final static int   THROTTLE_DURATION             = 1000;
    public final static int   DEFAULT_THREAD_COUNT          = 1;
    public final static int   DEFAULT_CONSUMER_COUNT        = 1;
    
    public static class Builder {
        private final MessageQueueDispatcher dispatcher = new MessageQueueDispatcher();
        
        /**
         * Specify the message queue to use for this dispatcher
         * @param messageQueue
         * @return
         */
        public Builder withMessageQueue(MessageQueue messageQueue) {
            dispatcher.messageQueue = messageQueue;
            return this;
        }
        
        /**
         * Change the number of threads reading from the queue
         * 
         * @param threadCount
         */
        public Builder withThreadCount(int threadCount) {
            dispatcher.threadCount = threadCount;
            return this;
        }
        
        /**
         * Set the number of consumers that will be removing items from the 
         * queue.  This value must be less than or equal to the thread count.
         * @param consumerCount
         * @return
         */
        public Builder withConsumerCount(int consumerCount) {
            dispatcher.consumerCount = consumerCount;
            return this;
        }
        
        /**
         * Use this external executor
         * @param executor
         * @return
         */
        public Builder withExecutor(ScheduledExecutorService executor) {
            dispatcher.executor = executor;
            return this;
        }
        
        /**
         * Number of 'triggers' to read from the queue in each call.  
         * Default is 1
         * @param batchSize
         */
        public Builder withBatchSize(int batchSize) {
            dispatcher.batchSize = batchSize;
            return this;
        }
        
        /**
         * Callback to process messages.  The callback is called from any of the internal processing
         * threads and is therefore not thread safe.
         * @param callback
         * @return true to ack the message, false to not ack and cause the message to timeout
         *          Throw an exception to force the message to be added to the poison queue
         */
        public Builder withCallback(Function<Message, Boolean> callback) {
            dispatcher.callback = callback;
            return this;
        }
        
        public MessageQueueDispatcher build() {
            Preconditions.checkArgument(dispatcher.consumerCount <= dispatcher.threadCount, "consumerCounter must be <= threadCount");
            dispatcher.initialize();
            return dispatcher;
        }
    }
    
    private int             threadCount   = DEFAULT_THREAD_COUNT;
    private int             batchSize     = DEFAULT_BATCH_SIZE;
    private int             consumerCount = DEFAULT_CONSUMER_COUNT;
    private boolean         terminate     = false;
    private MessageQueue    messageQueue;
    private ScheduledExecutorService executor;
    private boolean         bOwnedExecutor = false;
    private Function<Message, Boolean>   callback;
    private LinkedBlockingQueue<Message> toAck = Queues.newLinkedBlockingQueue();

    private MessageQueueDispatcher() {
    }
    
    private void initialize() {
        Preconditions.checkNotNull(messageQueue, "Must specify message queue");
    }
    
    public void start() {
        if (executor == null) {
            executor = Executors.newScheduledThreadPool(threadCount);
            bOwnedExecutor = true;
        }
        
        for (int i = 0; i < consumerCount; i++) {
            startConsumer(i);
        }
    }
    
    public void stop() {
        terminate = true;
        if (bOwnedExecutor) 
            executor.shutdownNow();
    }
    
    private void startConsumer(final int id) {
        final String name = StringUtils.join(Lists.newArrayList(messageQueue.getName(), "Consumer", Integer.toString(id)), ":");
        
        executor.submit(new Runnable() {
            @Override
            public void run() {
                if (terminate == true)
                    return;
                
                Thread.currentThread().setName(name);
                
                // Create the consumer context
                final MessageConsumer consumer = messageQueue.createConsumer();
                
                // Process events in a tight loop, until asked to terminate
                Collection<Message> messages = null;
                try {
                    messages = consumer.readMessages(batchSize);
                    for (final Message message : messages) {
                        executor.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (message.getTaskClass() != null) {
                                        @SuppressWarnings("unchecked")
                                        Function<Message, Boolean> task = (Function<Message, Boolean>)Class.forName(message.getTaskClass()).newInstance();
                                        if (task.apply(message)) {
                                            consumer.ackMessage(message);
                                        }
                                    }
                                    else if(callback.apply(message)) {
                                        consumer.ackMessage(message);
                                    }
                                }
                                catch (Throwable t) {
                                    try {
                                        consumer.ackPoisonMessage(message);
                                    } catch (MessageQueueException e) {
                                        LOG.warn("Failed to ack poison message", e);
                                    }
                                    // TODO: Add to poison queue
                                    LOG.error("Error processing message " + message.getKey(), t);
                                }
                            }
                        });
                    }
                } 
                catch (BusyLockException e) {
                }
                catch (Exception e) {
                    LOG.warn("Error consuming messages ", e);
                }
                
                executor.schedule(this,  THROTTLE_DURATION,  TimeUnit.MILLISECONDS);
            }
        });
    }
}
