import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.sarmat.perevodchik.phone.LlmPrompt

private val FI = listOf(
    "Hyvää päivää, soitan Kelasta. Hakemuksenne on käsittelyssä, mutta tarvitsemme vielä palkkatodistuksen.",
    "Voisitteko tulla vastaanotolle huomenna kello kymmenen?",
    "Työmarkkinatorilla on teille uusi työpaikkailmoitus rakennusalalta.",
    "Lähettäkää liitteet sähköisesti oma asiointi -palvelun kautta.",
    "Teidän työttömyysetuutenne maksetaan ensi viikolla.",
    "Mä soitan sulle huomenna uudestaan.",
    "Lapsenne opettaja haluaa sopia vanhempainvartin.",
    // как приходит из распознавания: без знаков, с ошибкой
    "voitko tulla vastaanotolla huomenna kello 10",
)
private val RU = listOf(
    "Здравствуйте, я хотел бы узнать, когда будет рассмотрено моё заявление.",
    "Извините, я плохо говорю по-фински, можно говорить медленнее?",
    "Я работал на стройке два месяца, но работа закончилась.",
    "Куда отправить справку о зарплате?",
    "Я не понял, повторите, пожалуйста.",
)

fun main(args: Array<String>) {
    val t0 = System.currentTimeMillis()
    val engine = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), cacheDir = "/tmp"))
    engine.initialize()
    println("load: ${(System.currentTimeMillis() - t0) / 1000.0}s")
    fun tr(text: String, src: String, tgt: String): String {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            thinkingConfig = ThinkingConfig(enableThinking = false),
            maxOutputToken = 400
        )
        return engine.createConversation(config).use { c ->
            val raw = c.sendMessage(Message.user(LlmPrompt.build(text, src, tgt))).toString()
            if (raw != LlmPrompt.clean(raw)) println("    [raw] ${raw.replace("\n", "\\n")}")
            LlmPrompt.clean(raw)
        }
    }
    for ((src, tgt, list) in listOf(Triple("fi", "ru", FI), Triple("ru", "fi", RU))) {
        println("\n### $src > $tgt")
        for (s in list) {
            val t = System.currentTimeMillis()
            val r = tr(s, src, tgt)
            println("  $s\n    -> $r   (${(System.currentTimeMillis() - t) / 1000.0}s)")
        }
    }
    engine.close()
}
