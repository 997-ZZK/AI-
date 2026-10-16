package com.aiflow.engine.memory.longterm;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 长期记忆的**禁写清单**（设计见 docs/long-memory-design.md §3.3）。
 *
 * <p>为什么必须在代码里再过一遍、而不是只写在提示词里：提示词是「模型会照做」，这里是「就算它不照做也写不进去」。
 * 记忆一旦落库会被后续每次对话召回 —— 一条不该记的东西不是「多了一行数据」，而是**在用户每次提问时重复复述**。
 *
 * <p>刻意的保守：宁可少记（用户会发现助手没记住，能再说一次），也不能错记（用户不会知道助手偷偷存了什么）。
 * 因此这里按**类别关键词**判，不确定就丢；真正的精细判断交给提示词。
 */
public final class MemoryWriteGuard {

    public static final String HEALTH = "health";
    public static final String POLITICS_RELIGION = "politics-religion";
    public static final String SEXUALITY = "sexuality";
    public static final String FINANCE = "finance";
    public static final String IDENTITY = "identity";
    public static final String CREDENTIAL = "credential";

    private static final List<Rule> RULES = List.of(
            new Rule(HEALTH, Pattern.compile("疾病|病症|确诊|症状|用药|服药|服用|药物|药品|剂量|医嘱|处方|手术|治疗|诊断|病史|过敏|体检"
                    + "|癌|抑郁|焦虑|心理疾病|失眠|怀孕|月经")),
            new Rule(POLITICS_RELIGION, Pattern.compile("政治|党派|党员|选举|投票|宗教|信仰|基督教|佛教|伊斯兰|穆斯林|教会|寺庙|教堂")),
            new Rule(SEXUALITY, Pattern.compile("性取向|同性恋|异性恋|双性恋|跨性别|性生活")),
            new Rule(FINANCE, Pattern.compile("银行卡|信用卡|储蓄卡|卡号|账户余额|支付宝|微信支付|网银|密码|口令|pin")),
            new Rule(IDENTITY, Pattern.compile("身份证|护照|手机号|电话号码|邮箱|email|住址|家庭住址|门牌|籍贯")),
            new Rule(CREDENTIAL, Pattern.compile("token|api key|api_key|apikey|secret|密钥|私钥|访问令牌|验证码"))
    );

    /** 身份证 / 手机号 / 长数字串 / 邮箱的字面形态：模型把号码写进 value 时，类别关键词拦不住它。 */
    private static final List<Pattern> SHAPES = List.of(
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            Pattern.compile("\\b\\d{15,19}[xX]?\\b"),
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}")
    );

    private MemoryWriteGuard() {
    }

    /** 命中返回类别名，未命中返回 null。 */
    public static String check(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(lowered).find()) {
                return rule.category();
            }
        }
        for (Pattern shape : SHAPES) {
            if (shape.matcher(lowered).find()) {
                return IDENTITY;
            }
        }
        return null;
    }

    private record Rule(String category, Pattern pattern) {
    }
}
