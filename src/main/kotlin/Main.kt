package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.Secret.API_KEY
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// 1. Модели данных для сериализации (Data Classes)

@Serializable
data class Message(
    val role: String, // "system", "user", "assistant"
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
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

// 2. Класс Агента

class RouterAIAgent(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String = "Ты полезный ассистент."
) {
    // Хранилище контекста (истории разговора)
    private val messageHistory: MutableList<Message> = mutableListOf(
        Message(role = "system", content = systemPrompt)
    )

    // Настройка JSON сериализатора (игнорирует неизвестные поля, чтобы API не ломало код)
    private val json = Json { ignoreUnknownKeys = true }

    // HTTP Клиент (Java 11+)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // Метод отправки сообщения
    fun sendMessage(userInput: String): String {
        // 1. Добавляем сообщение пользователя в историю
        messageHistory.add(Message(role = "user", content = userInput))

        // 2. Формируем тело запроса
        val requestBody = ChatRequest(
            model = model,
            messages = messageHistory
        )
        val requestBodyString = json.encodeToString(requestBody)

        // 3. Создаем HTTP запрос
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBodyString))
            .build()

        // 4. Выполняем запрос синхронно (можно использовать .sendAsync для асинхронности)
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                // 5. Парсим ответ
                val chatResponse = json.decodeFromString<ChatResponse>(response.body())

                // Проверка на ошибки в теле ответа
                if (chatResponse.error != null) {
                    return "API Error: ${chatResponse.error.message}"
                }

                val assistantMessage = chatResponse.choices?.firstOrNull()?.message

                if (assistantMessage != null) {
                    // 6. Сохраняем ответ ассистента в историю
                    messageHistory.add(assistantMessage)
                    assistantMessage.content
                } else {
                    "Error: No response choices found."
                }
            } else {
                "HTTP Error: ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Exception: ${e.message}"
        }
    }

    // Метод для очистки контекста
    fun resetContext() {
        messageHistory.clear()
        messageHistory.add(Message(role = "system", content = systemPrompt))
    }
}

// 3. Пример использования (Main)

fun main() {
    // Настройки (замените на свои)
    // Если это OpenRouter, URL обычно: "https://openrouter.ai/api/v1/chat/completions"
    val modelId = "deepseek/deepseek-chat-v3.1" // Пример модели

    val agent = RouterAIAgent(
        apiUrl = "https://routerai.ru/api/v1/chat/completions",
        apiKey = API_KEY,
        model = modelId
    )

    println("--- Чат начат (напишите 'exit' для выхода, 'clear' для сброса) ---")

    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: break

        if (input.equals("exit", ignoreCase = true)) break
        if (input.equals("clear", ignoreCase = true)) {
            agent.resetContext()
            println("Контекст очищен.")
            continue
        }

        val response = agent.sendMessage(input)
        println("Агент: $response")
    }
}