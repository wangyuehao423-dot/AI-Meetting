package com.example.interview.websocket;

import java.util.Map;
import java.util.TreeMap;

// ============================================================
// 语音转写会话上下文 —— 维护单个面试会话内的转写状态
// ============================================================
// 职责：
//   1. 按 seg_id 保存所有收到的转写分片（覆盖更新，不会产生重复）
//   2. 区分"临时结果"（ed=false）和"最终结果"（ed=true）
//   3. 按 seg_id 顺序拼接连续已确认分片，生成最终的完整转写文本
//   4. 提供线程安全的方法（synchronized），支持并发访问
//
// 关键设计决策：
//   - 使用 TreeMap 而非 HashMap：需要按 seg_id 顺序遍历
//   - put 是覆盖语义：同一 seg_id 后到的结果覆盖旧值，不会追加
//   - synchronized 方法：一个会话可能被多个线程同时操作
//     （WebSocket 接收线程、异步消费线程和业务处理线程）
// ============================================================
public class TranscriptionSessionContext {

    // ============================================================
    // 合并结果记录 —— mergeSegment 的返回值封装
    // ============================================================
    // 字段说明：
    //   preview     —— 临时预览文本（前端实时展示用，可能包含未确认的临时内容）
    //   transcript  —— 最终文本（只包含已确认的连续分片，适合落库）
    //   finalResult —— true 表示这是最终确认的结果，false 表示只是临时预览
    //   segId       —— 当前处理的分片 ID（原样透传，前端用于跟踪）
    //   pgs         —— 分片状态（原样透传，如 "speaking" / "silence"）
    //   rg          —— 音频范围起始位置（原样透传）
    //   bg          —— 分片开始时间位置（原样透传）
    // ============================================================
    public record MergeResult(
            String preview,      // 合并后的预览文本
            String transcript,   // 合并后的最终文本
            boolean finalResult, // 是否最终确认结果
            int segId,           // 当前分片 ID
            String pgs,          // 分片状态（原样透传）
            int rg,              // 音频范围起始（原样透传）
            int bg               // 分片开始时间（原样透传）
    ) {
    }

    // ---- 分片存储 ----
    // 按 seg_id 排序保存所有分片的最新文本。
    // key：seg_id（语音分片序号，从 0 开始递增）
    // value：分片文本（已 trim 处理）
    // 同一个 seg_id 后到的结果会覆盖旧值——这是 TreeMap.put 的自然行为。
    private final TreeMap<Integer, String> segmentMap = new TreeMap<>();

    // ---- 已确认分片 ----
    // 只保存 ed=true（最终结果）的分片。
    // 用于生成连续的最终句子，不会被临时结果污染。
    private final TreeMap<Integer, String> confirmedSegments = new TreeMap<>();

    // ---- 临时预览文本 ----
    // 保存当前看到的临时预览内容。
    // 由已确认句子 + 最新临时文本拼接而成。
    // 不直接作为最终答案落库。
    private String preview = "";

    // ---- 最终转写文本 ----
    // 只保存 ed=true 的最终转写结果。
    // 由连续已确认分片按 seg_id 顺序拼接而成。
    // 最终会存入答案表，作为面试答案的来源之一。
    private String transcript = "";

    // ============================================================
    // 核心方法：合并一个转写分片
    // ============================================================
    // 每次异步消费线程取出一个分片时调用此方法。
    //
    // 关键逻辑：
    //   1. 所有分片都存入 segmentMap（覆盖旧值）
    //   2. 如果 ed=true，同时存入 confirmedSegments，
    //      并重新拼接所有连续已确认分片，更新 transcript 和 preview
    //   3. 如果 ed=false，只拼接预览文本（confirmedSentence + 当前临时文本），
    //      不修改 transcript
    // ============================================================
    public synchronized MergeResult mergeSegment(
            int segId,
            String text,
            String pgs,
            int rg,
            int bg,
            boolean ed
    ) {
        // ---- 文本预处理 ----
        // 如果 text 为 null，转为空字符串并 trim（去除首尾空白）。
        // trim 的目的是去除 ASR 可能引入的前后空格。
        String normalized = text == null ? "" : text.trim();

        // ---- 覆盖更新分片 ----
        // TreeMap.put 是覆盖语义（replace），不是追加语义（append）。
        // 同一 seg_id 的多次到达只会保留最后一条文本，
        // 不会产生"我今今天去去公司"这样的重复错误。
        segmentMap.put(segId, normalized);

        // ---- 分支1：最终结果（ed=true） ----
        if (ed) {
            // 将当前分片加入"已确认"集合
            confirmedSegments.put(segId, normalized);

            // 从最小 seg_id 开始，拼接所有连续的已确认分片
            String sentence = rebuildConfirmedSentence();

            // 同时更新 transcript 和 preview（最终结果到达后两者完全同步）
            transcript = sentence;
            preview = sentence;

            return new MergeResult(
                    sentence,    // preview
                    sentence,    // transcript
                    true,        // finalResult（这是最终结果）
                    segId, pgs, rg, bg
            );
        }

        // ---- 分支2：临时结果（ed=false） ----
        // 临时结果只用于刷新预览，不能写入 transcript。
        // 这样可以防止"嗯...啊..."等临时修正痕迹出现在最终答案中。
        String temporaryPreview = rebuildPreview(segId, normalized);
        preview = temporaryPreview;

        return new MergeResult(
                temporaryPreview,  // preview（临时预览）
                transcript,        // transcript（保持之前的值不变）
                false,             // finalResult（这不是最终结果）
                segId, pgs, rg, bg
        );
    }

    // ============================================================
    // 私有方法：拼接连续已确认分片
    // ============================================================
    // 从最小 seg_id（即第一个分片）开始遍历 confirmedSegments，
    // 只拼接 seg_id 连续的分片。遇到不连续的分片就停止。
    //
    // 为什么只拼接连续分片？
    //   假设 confirmedSegments 中有 {0:"今天", 1:"天气", 3:"不错"}，
    //   seg_id=2 缺失（可能尚未到达或仍在处理中），
    //   此时只拼接 0 和 1（"今天天气"），不拼接 3，
    //   因为跳过 seg_id=2 直接拼接 3 会产生语义断裂。
    //   等 seg_id=2 到达并确认后，下一次调用会重新拼接所有连续分片。
    // ============================================================
    private String rebuildConfirmedSentence() {
        StringBuilder result = new StringBuilder();
        Integer expected = null;  // 预期的下一个 seg_id

        // TreeMap 的 entrySet 按 key 升序遍历
        for (Map.Entry<Integer, String> entry : confirmedSegments.entrySet()) {
            if (expected == null) {
                // 第一次迭代：以第一个分片的 seg_id 为起始
                expected = entry.getKey();
            }
            // 检查 seg_id 是否连续
            if (!entry.getKey().equals(expected)) {
                // 出现不连续（跳号），停止拼接
                // 例如 expected=2 但当前 entry.key=3，说明 seg_id=2 缺失
                break;
            }
            // 将当前分片文本附加到结果中
            appendText(result, entry.getValue());
            expected++;  // 期望下一个 seg_id
        }

        return result.toString();
    }

    // ============================================================
    // 私有方法：拼接临时预览文本
    // ============================================================
    // 临时预览 = 已确认句子 + 当前临时分片文本
    // 例如：
    //   已确认句子 = "今天天气"
    //   当前临时文本 = "真不错"
    //   预览结果 = "今天天气真不错"
    // ============================================================
    private String rebuildPreview(int segId, String temporaryText) {
        // 先获取当前已确认的连续句子
        String confirmed = rebuildConfirmedSentence();

        // 如果临时文本为空，预览仅包含已确认内容
        if (temporaryText.isBlank()) {
            return confirmed;
        }
        // 如果已确认句子为空，预览仅包含当前临时文本
        if (confirmed.isBlank()) {
            return temporaryText;
        }
        // 两者都不为空：已确认句子 + 临时文本
        // 用 confirmed + temporaryText 而不是 confirmed + " " + temporaryText，
        // 因为 appendText 已在已确认文本的末尾加了空格
        return confirmed + temporaryText;
    }

    // ============================================================
    // 私有方法：向 StringBuilder 中追加文本（自动加空格分隔）
    // ============================================================
    // 作用是在文本片段之间添加适当的分隔符：
    //   1. 如果 builder 为空，直接追加
    //   2. 如果 builder 末尾已经是空白字符，直接追加（不需要再加空格）
    //   3. 如果 builder 末尾不是空白字符，先加一个空格再追加
    // ============================================================
    private void appendText(StringBuilder builder, String value) {
        // 跳过 null 或空文本
        if (value == null || value.isBlank()) {
            return;
        }
        // 检查 builder 末尾是否是空白字符
        // 如果不是空白，先加一个空格再追加文本，避免词语粘连
        if (builder.length() > 0
                && !Character.isWhitespace(
                        builder.charAt(builder.length() - 1)
                )) {
            builder.append(' ');
        }
        // 追加文本内容
        builder.append(value);
    }

    // ============================================================
    // 公共方法：获取当前预览文本
    // ============================================================
    // 为什么是 synchronized？
    //   防止在 mergeSegment 正在进行时读取到不一致的中间状态。
    // ============================================================
    public synchronized String getPreview() {
        return preview;
    }

    // ============================================================
    // 公共方法：获取当前最终转写文本
    // ============================================================
    // 此方法被业务层调用，用于获取完整的面试答案文本。
    // transcript 只会在 ed=true 的分片到达时更新。
    // ============================================================
    public synchronized String getTranscript() {
        return transcript;
    }
}