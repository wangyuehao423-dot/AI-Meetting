// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个类）
package com.example.interview.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ============================================================
// 评分任务消费者循环
// ============================================================
// 职责：定时从评分队列拉取任务，交给 JudgeTaskPublisher.pullOnce() 处理。
//
// 为什么需要一个循环：pullOnce 用的是非阻塞的队列搬运（拿不到就返回 null），
// 不会像 consumeOnce 里带超时的阻塞弹出那样挂起等待。所以必须由调用方反复拉取，
// 否则任务会一直躺在队列里，补偿服务查库时看不到任何"已评分"的记录，
// 只会一遍遍重复补偿，等于空转。
//
// 为什么用 @Scheduled 而不是把循环写在方法里：
//   写在方法里就是常驻线程 + 忙等，一旦抛异常整个消费者永久退出；
//   @Scheduled(fixedDelay) 由 Spring 调度，单次异常只会跳过这一轮，
//   下一轮照常拉取，健壮性更好。
//   间隔取 200ms：队列为空时最多带来 5 次/秒的空查询，成本可忽略。
// ============================================================
@Component
public class JudgeTaskConsumerLoop {

    // 只依赖发布者：取任务、处理、失败重试都在 JudgeTaskPublisher 里完成，
    // 本类只负责"按节奏调用"，职责保持单一。
    private final JudgeTaskPublisher publisher;

    // 构造器注入，方便测试时替换成 mock。
    public JudgeTaskConsumerLoop(JudgeTaskPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 定时拉取一条评分任务。
     *
     * <p>fixedDelay 而不是 fixedRate：必须等上一轮处理完再开始计时，
     * 否则队列积压时会不断叠加新的拉取轮次。</p>
     */
    // 每轮处理完再等 200ms 触发下一次；队列为空时不会占着 CPU 空转。
    @Scheduled(fixedDelay = 200)
    public void poll() {
        // 一次只取一条：单个消费者不长时间占用线程；
        // 返回 false（队列已空）也直接结束本轮，等下一次调度即可。
        publisher.pullOnce();
    }
}
// 新增结束
