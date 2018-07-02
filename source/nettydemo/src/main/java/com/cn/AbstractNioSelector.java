package com.cn;

import com.cn.pool.NioSelectorRunnablePool;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 抽象selector线程类
 *
 * @author luyunzhou.luke
 *
 */
public abstract class AbstractNioSelector implements Runnable {

	/**
	 * 线程池
	 */
	private final Executor executor;

	/**
	 * 选择器
	 */
	protected Selector selector;

	/**
	 * 选择器wakenUp状态标记
	 */
	protected final AtomicBoolean wakenUp = new AtomicBoolean();

	/**
	 * 任务队列
	 */
	private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<Runnable>();

	/**
	 * 线程名称
	 */
	private String threadName;
	
	/**
	 * 线程管理对象
	 */
	protected NioSelectorRunnablePool selectorRunnablePool;

	AbstractNioSelector(Executor executor, String threadName, NioSelectorRunnablePool selectorRunnablePool) {
		this.executor = executor;
		this.threadName = threadName;
		this.selectorRunnablePool = selectorRunnablePool;
		openSelector();
	}

	/**
	 * 获取selector并启动线程
	 */
	private void openSelector() {
		try {
			this.selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException("Failed to create a selector.");
		}
		executor.execute(this);
	}

	@Override
	public void run() {
		
		Thread.currentThread().setName(this.threadName);
        // 多线程的本质就是加入一个管理selector的类，通过它去传递产生的ssc与sc，把ssc与sc的逻辑分开。
		while (true) {
			try {
				wakenUp.set(false);
				// boss
				// 发生条件：start() 新建ssc。 task队列加入task， 通过NioSelectorRunnablePool 获取一个boss线程 并唤醒
                //
                // worker
                // boss线程接收到sc。 task队列加入task，通过NioSelectorRunnablePool 获取一个worker线程 并唤醒
				select(selector);
				// boss
				// 消费task 消费逻辑为将ssc注册到selector
                //
                // worker
                // 消费task 消费逻辑为将sc注册到selector
				processTaskQueue();
                // 处理selector所监听的channel的事件
				process(selector);
			} catch (Exception e) {
				// ignore
			}
		}

	}

	/**
	 * 注册一个任务并激活selector
	 * 
	 * @param task
	 */
	protected final void registerTask(Runnable task) {
		taskQueue.add(task);

		Selector selector = this.selector;

		if (selector != null) {
			if (wakenUp.compareAndSet(false, true)) {
				selector.wakeup();
			}
		} else {
			taskQueue.remove(task);
		}
	}

	/**
	 * 执行队列里的任务
	 */
	private void processTaskQueue() {
		for (;;) {
			final Runnable task = taskQueue.poll();
			if (task == null) {
				break;
			}
			task.run();
		}
	}
	
	/**
	 * 获取线程管理对象
	 * @return
	 */
	public NioSelectorRunnablePool getSelectorRunnablePool() {
		return selectorRunnablePool;
	}

	/**
	 * select抽象方法
	 * 
	 * @param selector
	 * @return
	 * @throws IOException
	 */
	protected abstract int select(Selector selector) throws IOException;

	/**
	 * selector的业务处理
	 * 
	 * @param selector
	 * @throws IOException
	 */
	protected abstract void process(Selector selector) throws IOException;

}
