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

// 1. Модели данных

@Serializable
data class Message(
    val role: String,
    val content: String
) {
    val estimatedTokens: Int
        get() = (content.length / 4) + 4
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val error: ApiError? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ApiError(
    val message: String,
    val type: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

// Контейнер для сохранения состояния (для JSON файла)
@Serializable
data class AgentState(
    val summary: String? = null, // Текст саммари прошлых диалогов
    val recentMessages: List<Message> = emptyList() // Последние N сообщений
)

// 2. Класс Агента

class RouterAIAgent(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String = "Ты полезный ассистент.",
    private val historyFile: File,
    private val keepRecentMessages: Int = 10, // Сколько последних сообщений хранить "как есть"
    private val summarizeThreshold: Int = 10  // Каждые сколько сообщений делать сжатие
) {
    // Состояние агента
    private var summaryText: String? = null
    private val recentHistory: MutableList<Message> = mutableListOf()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    init {
        loadState()
        // Если история пуста, добавляем системный промпт
        if (recentHistory.isEmpty()) {
            recentHistory.add(Message(role = "system", content = systemPrompt))
        }
    }

    fun sendMessage(userInput: String): String {
        // 1. Добавляем сообщение пользователя
        recentHistory.add(Message(role = "user", content = userInput))

        // 2. Проверяем, нужно ли сжатие контекста
        // Учитываем только сообщения user/assistant (не system)
        val conversationLength = recentHistory.count { it.role != "system" }

        // Если превысили порог (например, 20 сообщений), запускаем суммаризацию
        // оставляя при этом keepRecentMessages (10) последних
        if (conversationLength >= (keepRecentMessages + summarizeThreshold)) {
            println("[System] Threshold reached. Summarizing context...")
            summarizeContext()
        }

        // 3. Формируем запрос к API
        val messagesForApi = buildMessagesForApi()

        val requestBody = ChatRequest(model = model, messages = messagesForApi)
        val requestBodyString = json.encodeToString(requestBody)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBodyString))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val chatResponse = json.decodeFromString<ChatResponse>(response.body())

                if (chatResponse.error != null) {
                    recentHistory.removeLast() // Откат
                    return "API Error: ${chatResponse.error.message}"
                }

                val assistantMessage = chatResponse.choices?.firstOrNull()?.message

                if (assistantMessage != null) {
                    recentHistory.add(assistantMessage)
                    saveState()

                    // Статистика токенов
                    val promptTokens = messagesForApi.sumOf { it.estimatedTokens }
                    printTokenStats(chatResponse.usage, promptTokens, assistantMessage.estimatedTokens)

                    assistantMessage.content
                } else {
                    recentHistory.removeLast()
                    "Error: No response choices found."
                }
            } else {
                recentHistory.removeLast()
                "HTTP Error: ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            recentHistory.removeLast()
            "Exception: ${e.message}"
        }
    }

    // Метод формирования сообщений для API: Summary + Recent History
    private fun buildMessagesForApi(): List<Message> {
        val fullList = mutableListOf<Message>()

        // 1. Если есть summary, добавляем его как System сообщение в начало
        if (!summaryText.isNullOrBlank()) {
            fullList.add(Message(role = "system", content = "Краткая сводка предыдущих разговоров: $summaryText"))
        }

        // 2. Добавляем последние сообщения
        // Важно: если summary нет, то первое сообщение в recentHistory должно быть system prompt
        fullList.addAll(recentHistory)

        return fullList
    }

    // Метод сжатия контекста
    private fun summarizeContext() {
        // Берем сообщения для сжатия (все, кроме последних keepRecentMessages)
        // Также исключаем текущий System Prompt (он всегда первый в recentHistory)

        // Индекс, до которого будем суммаризировать
        // Структура recentHistory: [SystemPrompt, User1, Asst1, User2, Asst2, ... UserLast]
        // Мы хотим оставить [SystemPrompt, ... UserLast-keepRecent]

        // Считаем сколько сообщений "сверху" забрать.
        // Если recentHistory = [Sys, U1, A1, U2, A2, U3, A3], keep=2.
        // Надо забрать [U1, A1] в summary. Оставить [Sys, U2, A2, U3, A3].

        // Вычисляем индекс разделителя.
        // System prompt (1) + (Total - keepRecent - 1) ?
        // Проще: взять срез от индекса 1 (пропуск system) до (size - keepRecent)

        val totalSize = recentHistory.size
        // Оставляем System (index 0) и последние N сообщений
        val splitIndex = totalSize - keepRecentMessages

        if (splitIndex <= 1) return // Нечего суммаризировать

        val toSummarize = recentHistory.subList(1, splitIndex).toList()

        if (toSummarize.isEmpty()) return

        // Формируем промпт для суммаризации
        val historyText = toSummarize.joinToString("\n") { "${it.role}: ${it.content}" }
        val summaryPrompt = """
            Проанализируй следующую часть диалога и составь краткую сводку (summary) основных фактов, решений и контекста.
            Диалог:
            $historyText
            
            Сводка (на русском языке):
        """.trimIndent()

        // Делаем отдельный запрос к LLM для получения summary
        val summaryResult = callLLMForSummary(summaryPrompt)

        if (summaryResult != null) {
            // Обновляем summary текст (добавляем к старому, если было)
            if (summaryText.isNullOrBlank()) {
                summaryText = summaryResult
            } else {
                summaryText += "\nНовые детали: $summaryResult"
            }

            // Удаляем старые сообщения из recentHistory
            // (удаляем элементы с 1 по splitIndex)
            // subList возвращает view, и clear() удаляет их из оригинального списка
            recentHistory.subList(1, splitIndex).clear()

            println("[System] Context summarized. New history size: ${recentHistory.size}")
            saveState()
        }
    }

    // Вспомогательный метод для вызова LLM (для суммаризации)
    private fun callLLMForSummary(prompt: String): String? {
        val messages = listOf(Message(role = "user", content = prompt))
        val requestBody = ChatRequest(model = model, messages = messages)

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
                chatResponse.choices?.firstOrNull()?.message?.content
            } else null
        } catch (e: Exception) {
            println("Error during summarization: ${e.message}")
            null
        }
    }

    private fun printTokenStats(apiUsage: Usage?, estimatedPrompt: Int, estimatedCompletion: Int) {
        println("\n--- Token Stats ---")
        println("Context sent tokens (Est): $estimatedPrompt")
        if (apiUsage != null) {
            println("API Reported Prompt: ${apiUsage.promptTokens}, Completion: ${apiUsage.completionTokens}")
        }
        println("--------------------")
    }

    fun resetContext() {
        recentHistory.clear()
        summaryText = null
        recentHistory.add(Message(role = "system", content = systemPrompt))
        saveState()
        println("Контекст полностью очищен.")
    }

    fun printCurrentState() {
        println("Summary exists: ${!summaryText.isNullOrBlank()}")
        println("Recent messages count: ${recentHistory.size}")
        val totalTokens = buildMessagesForApi().sumOf { it.estimatedTokens }
        println("Total active context tokens (Est): $totalTokens")
    }

    // Сохранение и загрузка состояния
    private fun saveState() {
        try {
            val state = AgentState(summary = summaryText, recentMessages = recentHistory)
            historyFile.writeText(json.encodeToString(state))
        } catch (e: Exception) {
            println("Error saving state: ${e.message}")
        }
    }

    private fun loadState() {
        try {
            if (historyFile.exists()) {
                val text = historyFile.readText()
                if (text.isNotBlank()) {
                    val state = json.decodeFromString<AgentState>(text)
                    this.summaryText = state.summary
                    this.recentHistory.clear()
                    this.recentHistory.addAll(state.recentMessages)
                    println("State loaded. Summary: ${if(summaryText != null) "Yes" else "No"}, Recent: ${recentHistory.size}")
                }
            }
        } catch (e: Exception) {
            println("Error loading state: ${e.message}")
        }
    }
}

// 3. Main

fun main() {
    val url = URL
    val key = API_KEY
    val modelId = DEFAULT_MODEL // Или deepseek/deepseek-chat

    // Файл теперь хранит структуру AgentState
    val historyFile = File("agent_state.json")

    // keepRecentMessages = 4 (2 пары вопрос-ответ)
    // summarizeThreshold = 4 (сжимать каждые 4 сообщения сверх лимита)
    val agent = RouterAIAgent(
        apiUrl = url,
        apiKey = key,
        model = modelId,
        historyFile = historyFile,
        keepRecentMessages = 2, // Хранить только 2 последних сообщения
        summarizeThreshold = 2  // Сжимать каждые 2 сообщения сверх лимита
    )

    println("--- Agent with Summary Context ---")
    println("Commands: 'exit', 'clear', 'stats'")

    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: break

        if (input.equals("exit", ignoreCase = true)) break
        if (input.equals("clear", ignoreCase = true)) {
            agent.resetContext()
            continue
        }
        if (input.equals("stats", ignoreCase = true)) {
            agent.printCurrentState()
            continue
        }

        val response = agent.sendMessage(input)
        println("Агент: $response")
    }
}
