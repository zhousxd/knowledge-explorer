package com.ke.service.entry;

import com.ke.domain.entry.NlIntent;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

/**
 * 入口意图分类器（FR-N01 / 02 §5.3）：用户一句话 → 四类白名单意图 {@link NlIntent}。
 * 走路由档小模型（{@link ModelTier#ROUTER}，近零成本，02 §6 模型分级路由），提示词在
 * intent-prompts.properties（要求模型只输出枚举名）；输出经 {@link NlIntent#parse}
 * trim+upper 精确匹配，解析失败/非法枚举/空白输入一律兜底 {@link NlIntent#OUT_OF_SCOPE}
 * ——安全侧错报优于漏报：不确定的请求宁可礼貌拒绝，不可误入讲解/比较通道产生误导性产出。
 * 网关自身异常不在此吞掉（与讲解流水线同语义：基础设施故障向上传播，由调用方按错误响应，
 * 而非伪装成「不支持请求」掩盖故障）。
 *
 * <p>输入长度：HTTP 层（Task 27 的 POST /entries/nl-draft）对 &gt;200 字拒收 400；
 * 分类器内部另有 500 字防御性截断，保证非 HTTP 调用路径下提示词输入仍有界。
 * 本任务只交付分类器（Task 27 草稿端点调用），不暴露 HTTP 端点。
 */
@Service
@PropertySource(value = "classpath:intent-prompts.properties", encoding = "UTF-8")
public class NlIntentClassifier {

    /** 防御性截断上限：上层（Task 27）在 200 拒收，这里兜底防超长输入打爆提示词 */
    static final int MAX_INPUT_CHARS = 500;

    private final LlmGateway llm;

    @Value("${intent.prompt.system}")
    private String systemPrompt;

    public NlIntentClassifier(LlmGateway llm) {
        this.llm = llm;
    }

    /** 分类一句话输入；空白输入不打网关直接安全侧结论，解析失败兜底见类注释 */
    public NlIntent classify(String text) {
        if (text == null || text.isBlank()) {
            return NlIntent.OUT_OF_SCOPE;
        }
        String user = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        String answer = llm.complete(new ChatCommand(systemPrompt, user, ModelTier.ROUTER));
        return NlIntent.parse(answer).orElse(NlIntent.OUT_OF_SCOPE);
    }
}
