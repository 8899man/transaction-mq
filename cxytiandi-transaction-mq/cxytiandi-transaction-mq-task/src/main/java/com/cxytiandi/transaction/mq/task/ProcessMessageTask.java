package com.cxytiandi.transaction.mq.task;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.alibaba.dubbo.config.annotation.Reference;
import com.cxytiandi.jdbc.util.DateUtils;
import com.cxytiandi.transaction.mq.api.dto.MessageDto;
import com.cxytiandi.transaction.mq.api.po.TransactionMessage;
import com.cxytiandi.transaction.mq.api.rpc.TransactionMessageRpcService;

@Service
public class ProcessMessageTask {
	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessMessageTask.class);
	
	@Reference(interfaceClass = TransactionMessageRpcService.class, version = "1.0.0", check = false, mock = "true")
	private TransactionMessageRpcService transactionMessageRpcService;
	
	@Autowired
	private Producer producer;
	
	@Autowired
	private RedissonClient redisson;
	
	private ExecutorService fixedThreadPool = Executors.newFixedThreadPool(10);
	
	private Semaphore semaphore = new Semaphore(20);
	
	public void start() {
		final RLock lock = redisson.getLock("transaction-mq-task");
		Thread th = new Thread(new Runnable() {
			
			public void run() {
				while(true) {
					try {
						lock.lock();
						System.out.println("开始发送消息:" + DateUtils.date2Str(new Date()));
						int sleepTime = process();
						if (sleepTime > 0) {
							Thread.sleep(10000);
						}
					} catch (Exception e) {
						LOGGER.error("", e);
					} finally {
						lock.unlock();
					}
				}
			}
		});
		th.start();
	}
	
	private int process() throws Exception {
		int sleepTime = 10000;	//默认执行完之后等等10秒
		List<TransactionMessage> messageList = transactionMessageRpcService.findByWatingMessage(5000);
		//如果消息多，则执行完之后不等待，直接继续执行
		if (messageList.size() == 5000) {
			sleepTime = 0;
		}
		final CountDownLatch latch = new CountDownLatch(messageList.size());
		for (final TransactionMessage message : messageList) {
			semaphore.acquire();
			fixedThreadPool.execute(new Runnable() {
				
				public void run() {
					try {
						System.out.println("发送具体消息：" + message.getId());
						doProcess(message);
					} catch (Exception e) {
						LOGGER.error("", e);
					} finally {
						semaphore.release();
						latch.countDown();
					}
				}
			});
		}
		latch.await();
		return sleepTime;
	}
	
	private void doProcess(TransactionMessage message) {
		//检查此消息是否满足死亡条件
		if (message.getSendCount() > message.getDieCount()) {
			transactionMessageRpcService.confirmDieMessage(message.getId());
			return;
		}
		
		//距离上次发送时间超过一分钟才继续发送
		long currentTime = System.currentTimeMillis();
		long sendTime = 0;
		if (message.getSendDate() != null) {
			sendTime = message.getSendDate().getTime();
		}
		if (currentTime - sendTime > 60000) {
			
			//向MQ发送消息
			MessageDto messageDto = new MessageDto();
			messageDto.setMessageId(message.getId());
			messageDto.setMessage(message.getMessage());
			producer.send(message.getQueue(), JsonUtils.toJson(messageDto));
			
			//修改消息发送次数以及最近发送时间
			transactionMessageRpcService.incrSendCount(message.getId(), new Date());
			
		}
	}
}
