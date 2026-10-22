// 从这里开始是新增的：以下整个文件都是新增的（补全亮点.md 里只写了
// FollowUpTaskPublisher 的消费方法，并注明"调用频率由外部定时任务/循环控制"，
// 但项目里没有任何一处调用它。没有这个循环类，追问队列就进得去出不来，
// 追问永远生成不了，属于"能编译但跑不通"的缺口，因此补上本类。）
package com.example.interview.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ============================================================
// 追问任务消费者循环
// ============================================================
// 职责：定时触发 FollowUpTaskPublisher.consumeOnce()，让"消费一条追问任务"
// 变成持续运行的动作。
//
// 为什么需要一个循环：consumeOnce 上带 @Async("followUpTaskExecutor")，
// 它的语义是"被调用一次、在独立线程池里消费一条"，调用方拿到的是立即返回的
// Future 占位，不会自己重复执行；没有外部反复调用，Redis 主队列里的追问任务
// 会一直躺着，评分返回的 chain 字段也就永远等不到追问落库。
//
// 为什么用 @Scheduled 而不是在方法里写 while(true)：
//   常驻循环一旦抛异常就永久退出；@Scheduled(fixedDelay) 由 Spring 调度，
//   单次异常只跳过这一轮，下一轮照常触发，健壮性更好。
//   间隔取 200ms：队列为空时最多 5 次/秒的空查询，成本可忽略。
// ============================================================
@Component
public class FollowUpTaskConsumerLoop {

    // 只依赖发布/消费服务：出队、反序列化、调生成服务、失败重投或进死信
    // 都在 FollowUpTaskPublisher 里完成，本类只负责"按节奏触发"，职责单一。
    private final FollowUpTaskPublisher publisher;

    // 构造器注入，方便测试时替换成 mock。
    public FollowUpTaskConsumerLoop(FollowUpTaskPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 定时触发一次追问任务消费。
     *
     * <p>为什么调 consumeOnce 而不是自己写拉取逻辑：consumeOnce 内部用的是
     * leftPopAndLeftPush 这条原子命令（主队列 → processing），
     * 队列为空时最多阻塞 200ms 就返回，正好适合被定时器反复调用；
     * 在这里重写一遍出队逻辑只会多出一份容易走样的拷贝。</p>
     *
     * <p>为什么用 fixedDelay 而不是 fixedRate：必须等上一轮触发完再开始计时，
     * 队列积压时不会不断叠加新的触发轮次。</p>
     */
    @Scheduled(fixedDelay = 200)
    public void poll() {
        // 一次只触发一条：单轮不长时间占用调度线程；
        // 队列为空时 consumeOnce 会立刻返回，等下一次调度即可。
        publisher.consumeOnce();
    }
}
// 新增结束
