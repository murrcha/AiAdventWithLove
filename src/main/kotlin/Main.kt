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
    // Вспомогательное свойство для оценки токенов в этом сообщении
    // Формула: (длина строки / 4) + 4 токена на служебные нужды (role, форматирование json)
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
    val usage: Usage? = null, // Добавляем поле usage для получения точных данных от API
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

// Новая модель для статистики использования токенов (если API возвращает)
@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

// 2. Класс Агента

class RouterAIAgent(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String = "Ты полезный ассистент.",
    private val historyFile: File
) {
    private val messageHistory: MutableList<Message> = mutableListOf()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    init {
        loadHistory()
        if (messageHistory.isEmpty()) {
            messageHistory.add(Message(role = "system", content = systemPrompt))
        }
    }

    fun sendMessage(userInput: String): String {
        val userMessage = Message(role = "user", content = userInput)
        messageHistory.add(userMessage)

        // 1. Оценка токенов запроса (Prompts)
        // Это сумма токенов всей истории, которую мы шлем в API
        val promptTokensEstimated = messageHistory.sumOf { it.estimatedTokens }

        val requestBody = ChatRequest(model = model, messages = messageHistory)
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
                    messageHistory.removeLast()
                    return "API Error: ${chatResponse.error.message}"
                }

                val assistantMessage = chatResponse.choices?.firstOrNull()?.message

                if (assistantMessage != null) {
                    messageHistory.add(assistantMessage)
                    saveHistory()

                    // 2. Подсчет токенов ответа
                    val completionTokensEstimated = assistantMessage.estimatedTokens

                    // Вывод статистики
                    printTokenStats(
                        apiUsage = chatResponse.usage, // Точные данные от API (если есть)
                        estimatedPrompt = promptTokensEstimated,
                        estimatedCompletion = completionTokensEstimated
                    )

                    assistantMessage.content
                } else {
                    messageHistory.removeLast()
                    "Error: No response choices found."
                }
            } else {
                messageHistory.removeLast()
                "HTTP Error: ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            messageHistory.removeLast()
            "Exception: ${e.message}"
        }
    }

    // Метод вывода статистики
    private fun printTokenStats(apiUsage: Usage?, estimatedPrompt: Int, estimatedCompletion: Int) {
        println("\n--- Token Stats ---")

        // Выводим оценку (всегда доступна)
        println("Estimated Prompt Tokens (History): $estimatedPrompt")
        println("Estimated Completion Tokens: $estimatedCompletion")

        // Если API вернуло точные данные (OpenAI, DeepSeek часто это делают), выводим их
        if (apiUsage != null) {
            println("API Reported Prompt Tokens: ${apiUsage.promptTokens}")
            println("API Reported Completion Tokens: ${apiUsage.completionTokens}")
            println("API Reported Total Tokens: ${apiUsage.totalTokens}")
        } else {
            println("API did not report usage stats.")
        }
        println("--------------------")
    }

    fun resetContext() {
        messageHistory.clear()
        messageHistory.add(Message(role = "system", content = systemPrompt))
        saveHistory()
        println("Контекст очищен.")
    }

    // Функция для просмотра текущего расхода токенов без запроса
    fun printCurrentContextSize() {
        val total = messageHistory.sumOf { it.estimatedTokens }
        println("Current context size (estimated): $total tokens")
    }

    private fun saveHistory() {
        try {
            historyFile.writeText(json.encodeToString(messageHistory))
        } catch (e: Exception) {
            println("Ошибка сохранения истории: ${e.message}")
        }
    }

    private fun loadHistory() {
        try {
            if (historyFile.exists()) {
                val text = historyFile.readText()
                if (text.isNotBlank()) {
                    val loadedMessages = json.decodeFromString<List<Message>>(text)
                    messageHistory.clear()
                    messageHistory.addAll(loadedMessages)
                    println("История загружена (${messageHistory.size} сообщений).")
                }
            }
        } catch (e: Exception) {
            println("Ошибка загрузки истории: ${e.message}")
            messageHistory.clear()
        }
    }
}

// 3. Main

fun main() {
    val url = URL
    val key = API_KEY
    val modelId = DEFAULT_MODEL
    val historyFile = File("chat_history.json")

    val agent = RouterAIAgent(
        apiUrl = url,
        apiKey = key,
        model = modelId,
        historyFile = historyFile
    )

    println("--- Чат начат (напишите 'exit' для выхода, 'clear' для сброса, 'stats' для размера контекста) ---")

    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: break

        if (input.equals("exit", ignoreCase = true)) break
        if (input.equals("clear", ignoreCase = true)) {
            agent.resetContext()
            continue
        }
        if (input.equals("stats", ignoreCase = true)) {
            agent.printCurrentContextSize()
            continue
        }

        val response = agent.sendMessage(input)
        println("Агент: $response")
    }
}