package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.Secret.API_KEY
import org.example.Secret.DEFAULT_MODEL
import org.example.Secret.URL
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.io.File

// --- 1. Модели данных ---

@Serializable
data class Message(val role: String, val content: String) {
    val estimatedTokens: Int get() = (content.length / 4) + 4
}

@Serializable
data class ChatRequest(val model: String, val messages: List<Message>)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val error: ApiError? = null
)

@Serializable
data class Choice(val index: Int, val message: Message, @SerialName("finish_reason") val finishReason: String? = null)

@Serializable
data class ApiError(val message: String, val type: String? = null)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

// Состояние для стратегии Facts
@Serializable
data class FactStore(val facts: MutableList<String> = mutableListOf())

// Состояние для стратегии Branching
@Serializable
data class BranchData(
    val branches: MutableMap<String, List<Message>> = mutableMapOf(),
    var activeBranch: String = "main"
)

// Единое состояние агента
@Serializable
data class AgentState(
    var strategyType: String = "SLIDING_WINDOW",
    var slidingHistory: MutableList<Message> = mutableListOf(), // Для Sliding Window
    var factsStore: FactStore = FactStore(),         // Для Sticky Facts
    var branchData: BranchData = BranchData(),       // Для Branching
    var stickyHistory: MutableList<Message> = mutableListOf()   // История для Sticky (короткая)
)

// --- 2. Стратегии управления контекстом ---

interface ContextStrategy {
    fun getMessagesForApi(systemPrompt: String): List<Message>
    fun addUserMessage(message: Message)
    fun addAssistantMessage(message: Message)
    fun getStats(): String
    fun reset(systemPrompt: String)
}

// Стратегия 1: Sliding Window (Скользящее окно)
class SlidingWindowStrategy(
    private val state: AgentState,
    private val windowSize: Int = 2,
) : ContextStrategy {

    override fun getMessagesForApi(systemPrompt: String): List<Message> {
        val messages = state.slidingHistory.toMutableList()
        // Убеждаемся, что System Prompt всегда первый
        if (messages.isEmpty() || messages.first().role != "system") {
            messages.add(0, Message("system", systemPrompt))
        }
        return messages
    }

    override fun addUserMessage(message: Message) {
        state.slidingHistory.add(message)
        trimHistory()
    }

    override fun addAssistantMessage(message: Message) {
        state.slidingHistory.add(message)
        trimHistory()
    }

    private fun trimHistory() {
        // Оставляем System + N последних сообщений
        // Индекс 0 - System. Если всего > windowSize + 1, удаляем с 1 по (size - windowSize)
        if (state.slidingHistory.size > windowSize + 1) {
            val keepCount = windowSize + 1
            val history = state.slidingHistory.takeLast(keepCount)
            state.slidingHistory.clear()
            state.slidingHistory.addAll(history)
            println("[Strategy: Sliding Window] Trimmed history to last $windowSize messages.")
        }
    }

    override fun getStats(): String = "Messages in context: ${state.slidingHistory.size}"
    override fun reset(systemPrompt: String) {
        state.slidingHistory = mutableListOf(Message("system", systemPrompt))
    }
}

// Стратегия 2: Sticky Facts (Факты)
class StickyFactsStrategy(
    private val state: AgentState,
    private val llmCaller: (String) -> String? // Функция для вызова LLM
) : ContextStrategy {

    override fun getMessagesForApi(systemPrompt: String): List<Message> {
        val messages = mutableListOf<Message>()

        // 1. System Prompt
        messages.add(Message("system", systemPrompt))

        // 2. Facts Block
        if (state.factsStore.facts.isNotEmpty()) {
            val factsText = state.factsStore.facts.joinToString("\n")
            messages.add(Message("system", "Важные факты из диалога:\n$factsText"))
        }

        // 3. Recent History
        messages.addAll(state.stickyHistory)

        return messages
    }

    override fun addUserMessage(message: Message) {
        updateFacts(message.content) // Извлекаем факты
        state.stickyHistory.add(message)
        trimHistory()
    }

    override fun addAssistantMessage(message: Message) {
        state.stickyHistory.add(message)
        trimHistory()
    }

    private fun trimHistory() {
        // Храним меньше истории, так как факты в отдельном блоке
        val limit = 5
        if (state.stickyHistory.size > limit) {
            val history = state.stickyHistory.takeLast(limit)
            state.stickyHistory.clear()
            state.stickyHistory.addAll(history)
        }
    }

    private fun updateFacts(userInput: String) {
        val prompt = """
            Проанализируй сообщение пользователя и извлеки из него ключевые факты (имена, даты, предпочтения, решения).
            Верни результат ТОЛЬКО в формате строки: "fact1,fact2,...,fact10".
            Если фактов нет, верни пустую строку.
            Сообщение: "$userInput"
        """.trimIndent()

        val result = llmCaller(prompt)
        if (result != null) {
            try {
                // Простая эвристика для парсинга JSON без сложных библиотек, если ответ пришел с markdown
                //val jsonStr = result.substringAfter("{").substringBefore("}")
                // В реальном проекте используйте Json.decodeFromString
                //val resultMap: Map<String, String> = Json.decodeFromString(result)
                val facts = result.split(",")
                state.factsStore.facts.addAll(facts)
                // Здесь упрощение: мы просто обновляем мапу
                println("[Strategy: Facts] Detected potential facts: $result")
                // Для демо просто добавим новый факт (в реальности нужно парсить и мержить)
                // Здесь мы эмулируем обновление, в реальном коде нужен парсер Map<String, String>
                // Для простоты, добавим в лог, что факт обновлен.
            } catch (e: Exception) {
                println("[Strategy: Facts] Failed to parse facts: $e")
            }
        }
    }

    override fun getStats(): String = "Facts count: ${state.factsStore.facts.size}, Recent msgs: ${state.stickyHistory.size}"
    override fun reset(systemPrompt: String) {
        state.factsStore = FactStore()
        state.stickyHistory = mutableListOf(Message("system", systemPrompt))
    }
}

// Стратегия 3: Branching (Ветвление)
class BranchingStrategy(
    private val state: AgentState
) : ContextStrategy {

    override fun getMessagesForApi(systemPrompt: String): List<Message> {
        val currentBranch = state.branchData.activeBranch
        val messages = state.branchData.branches[currentBranch]?.toMutableList() ?: mutableListOf()

        if (messages.isEmpty() || messages.first().role != "system") {
            messages.add(0, Message("system", systemPrompt))
        }
        return messages
    }

    override fun addUserMessage(message: Message) {
        val branch = state.branchData.activeBranch
        val list = state.branchData.branches.getOrPut(branch) { mutableListOf() }
        (list as MutableList).add(message)
    }

    override fun addAssistantMessage(message: Message) {
        val branch = state.branchData.activeBranch
        val list = state.branchData.branches.getOrPut(branch) { mutableListOf() }
        (list as MutableList).add(message)
    }

    fun createBranch(newId: String, fromCheckpoint: Int? = null) {
        // Копируем текущую ветку в новую
        val current = state.branchData.branches[state.branchData.activeBranch] ?: emptyList()
        val checkpoint = fromCheckpoint ?: current.size // Если не указано, копируем всё

        // Копируем часть истории (например, первые N сообщений)
        val newHistory = current.take(checkpoint).toMutableList()
        state.branchData.branches[newId] = newHistory
        println("[Strategy: Branching] Created branch '$newId' with ${newHistory.size} messages.")
    }

    fun switchBranch(id: String) {
        if (state.branchData.branches.containsKey(id)) {
            state.branchData.activeBranch = id
            println("[Strategy: Branching] Switched to branch '$id'.")
        } else {
            println("[Strategy: Branching] Branch '$id' not found.")
        }
    }

    override fun getStats(): String {
        val branches = state.branchData.branches.keys.joinToString(", ")
        return "Active: ${state.branchData.activeBranch}, All branches: $branches"
    }

    override fun reset(systemPrompt: String) {
        state.branchData = BranchData(
            branches = mutableMapOf("main" to mutableListOf(Message("system", systemPrompt))),
            activeBranch = "main"
        )
    }
}

// --- 3. Агент ---

class RouterAIAgent(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String = "Ты полезный ассистент.",
    private val stateFile: File
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var state = AgentState()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    // Текущая стратегия (делегат)
    private var currentStrategy: ContextStrategy = SlidingWindowStrategy(state)

    init {
        loadState()
        initStrategy()
    }

    private fun initStrategy() {
        currentStrategy = when (state.strategyType) {
            "SLIDING_WINDOW" -> SlidingWindowStrategy(state)
            "FACTS" -> StickyFactsStrategy(state) { prompt -> callLLMRaw(prompt) }
            "BRANCHING" -> BranchingStrategy(state)
            else -> SlidingWindowStrategy(state)
        }
        currentStrategy.reset(systemPrompt) // Сброс при смене стратегии или инициализация
        println("Initialized strategy: ${state.strategyType}")
    }

    fun setStrategy(type: String) {
        state.strategyType = type
        initStrategy()
        saveState()
    }

    fun sendMessage(userInput: String): String {
        val userMessage = Message("user", userInput)
        currentStrategy.addUserMessage(userMessage)

        val messagesForApi = currentStrategy.getMessagesForApi(systemPrompt)
        val requestBody = ChatRequest(model, messagesForApi)

        // ... (HTTP Request logic same as before) ...
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val chatResponse = json.decodeFromString<ChatResponse>(response.body())
                if (chatResponse.error != null) return "Error: ${chatResponse.error.message}"

                val assistantMsg = chatResponse.choices?.firstOrNull()?.message
                if (assistantMsg != null) {
                    currentStrategy.addAssistantMessage(assistantMsg)
                    saveState()
                    println(currentStrategy.getStats())
                    assistantMsg.content
                } else "Error: No response"
            } else "HTTP Error: ${response.body()}"
        } catch (e: Exception) {
            e.printStackTrace()
            "Exception: ${e.message}"
        }
    }

    // Специфичные команды для Branching
    fun createBranch(name: String) {
        if (currentStrategy is BranchingStrategy) {
            (currentStrategy as BranchingStrategy).createBranch(name)
            saveState()
        } else {
            println("Command only available in BRANCHING mode.")
        }
    }

    fun switchBranch(name: String) {
        if (currentStrategy is BranchingStrategy) {
            (currentStrategy as BranchingStrategy).switchBranch(name)
            saveState()
        } else {
            println("Command only available in BRANCHING mode.")
        }
    }

    // Вспомогательный вызов LLM для Facts
    private fun callLLMRaw(prompt: String): String? {
        val messages = listOf(Message("user", prompt))
        val req = ChatRequest(model, messages)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(req)))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            json.decodeFromString<ChatResponse>(response.body()).choices?.firstOrNull()?.message?.content
        } catch (e: Exception) { null }
    }

    private fun saveState() {
        stateFile.writeText(json.encodeToString(state))
    }

    private fun loadState() {
        if (stateFile.exists()) {
            try {
                state = json.decodeFromString(stateFile.readText())
            } catch (e: Exception) { println("Failed to load state: ${e.message}") }
        }
    }

    fun resetAll() {
        state = AgentState()
        initStrategy()
        saveState()
    }
}

// --- 4. Main ---

fun main() {
    val url = URL
    val key = API_KEY
    val modelId = DEFAULT_MODEL
    val stateFile = File("agent_multi_strategy.json")

    val agent = RouterAIAgent(url, key, modelId, stateFile = stateFile)

    println("Agent Ready. Commands: /strategy <window|facts|branch>, /branch <new|switch> <name>, /reset, exit")

    while (true) {
        print("You: ")
        val input = readlnOrNull() ?: break

        if (input.startsWith("exit")) return

        if (input.startsWith("/strategy")) {
            val type = input.split(" ").getOrNull(1) ?: ""
            when(type) {
                "window" -> agent.setStrategy("SLIDING_WINDOW")
                "facts" -> agent.setStrategy("FACTS")
                "branch" -> agent.setStrategy("BRANCHING")
                else -> println("Unknown strategy. Use: window, facts, branch")
            }
            continue
        }

        if (input.startsWith("/branch")) {
            val parts = input.split(" ")
            if (parts.size >= 3) {
                val cmd = parts[1]
                val name = parts[2]
                if (cmd == "new") agent.createBranch(name)
                if (cmd == "switch") agent.switchBranch(name)
            } else {
                println("Usage: /branch new <name> OR /branch switch <name>")
            }
            continue
        }

        if (input == "/reset") {
            agent.resetAll()
            continue
        }

        val response = agent.sendMessage(input)
        println("Agent: $response")
    }
}