package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.Secret.API_KEY
import org.example.Secret.DEFAULT_MODEL
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.io.File

// 1. Модели данных для сериализации (Data Classes)

@Serializable
data class Message(
    val role: String,
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
    private val systemPrompt: String = "Ты полезный ассистент.",
    private val historyFile: File // Добавили параметр файла для истории
) {
    // Хранилище контекста
    private val messageHistory: MutableList<Message> = mutableListOf()

    // Настройка JSON сериализатора
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true // Для красивого форматирования файла истории
    }

    // HTTP Клиент
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    init {
        // При инициализации агента пытаемся загрузить историю
        loadHistory()
        // Если история пуста (файла не было), добавляем системный промпт
        if (messageHistory.isEmpty()) {
            messageHistory.add(Message(role = "system", content = systemPrompt))
        }
    }

    // Метод отправки сообщения
    fun sendMessage(userInput: String): String {
        // Добавляем сообщение пользователя
        messageHistory.add(Message(role = "user", content = userInput))

        val requestBody = ChatRequest(
            model = model,
            messages = messageHistory
        )
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
                    // Если ошибка API, удаляем последнее сообщение пользователя, чтобы не ломать контекст
                    messageHistory.removeLast()
                    return "API Error: ${chatResponse.error.message}"
                }

                val assistantMessage = chatResponse.choices?.firstOrNull()?.message

                if (assistantMessage != null) {
                    messageHistory.add(assistantMessage)
                    // Сохраняем историю после успешного ответа
                    saveHistory()
                    assistantMessage.content
                } else {
                    messageHistory.removeLast() // Откат
                    "Error: No response choices found."
                }
            } else {
                messageHistory.removeLast() // Откат
                "HTTP Error: ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            messageHistory.removeLast() // Откат при исключении
            "Exception: ${e.message}"
        }
    }

    // Метод для очистки контекста
    fun resetContext() {
        messageHistory.clear()
        messageHistory.add(Message(role = "system", content = systemPrompt))
        saveHistory() // Сохраняем пустую историю (только с system prompt)
        println("Контекст очищен и сохранен.")
    }

    // Новые методы для работы с файлом

    private fun saveHistory() {
        try {
            // Записываем текущий список сообщений в файл
            historyFile.writeText(json.encodeToString(messageHistory))
        } catch (e: Exception) {
            println("Ошибка сохранения истории: ${e.message}")
        }
    }

    private fun loadHistory() {
        try {
            if (historyFile.exists()) {
                // Читаем файл и десериализуем в список
                val text = historyFile.readText()
                if (text.isNotBlank()) {
                    val loadedMessages = json.decodeFromString<List<Message>>(text)
                    messageHistory.clear()
                    messageHistory.addAll(loadedMessages)
                    println("История загружена (${messageHistory.size} сообщений).")
                }
            }
        } catch (e: Exception) {
            println("Ошибка загрузки истории: ${e.message}. Начинаем с чистого листа.")
            messageHistory.clear()
        }
    }
}

// 3. Пример использования (Main)

fun main() {
    val url = "https://routerai.ru/api/v1/chat/completions"
    val key = API_KEY
    val modelId = DEFAULT_MODEL

    // Определяем файл для хранения истории (в корне проекта)
    val historyFile = File("chat_history.json")

    val agent = RouterAIAgent(
        apiUrl = url,
        apiKey = key,
        model = modelId,
        historyFile = historyFile
    )

    println("--- Чат начат (напишите 'exit' для выхода, 'clear' для сброса) ---")
    println("История хранится в файле: ${historyFile.absolutePath}")

    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: break

        if (input.equals("exit", ignoreCase = true)) break
        if (input.equals("clear", ignoreCase = true)) {
            agent.resetContext()
            continue
        }

        val response = agent.sendMessage(input)
        println("Агент: $response")
    }
}