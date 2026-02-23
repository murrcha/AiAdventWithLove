package org.example


import kotlinx.serialization.json.Json
import org.example.Secret.API_KEY
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess
import kotlin.time.measureTime

fun main() {
    // 1) deepseek/deepseek-v3.2
    // 2) z-ai/glm-4-32b
    // 3) openai/gpt-5-nano
    // У меня есть 5 яблок. Я отдал 2 другу. Потом купил еще 7. Потом съел 3. Сколько яблок у меня осталось? Распиши свои действия по шагам.
    val apiKey = API_KEY // Замените на ваш API-ключ

    val model = "openai/gpt-5-nano" // Или любая другая модель из OpenRouter

    val client = HttpClient.newHttpClient()

    println("🚀 Чат с ИИ запущен. Введите 'стоп' для выхода.")

    while (true) {
        print("Вы: ")
        val userInput = readLine() ?: continue

        if (userInput.equals("стоп", ignoreCase = true)) {
            println("👋 Завершение работы...")
            exitProcess(0)
        }

        val requestBody = """
        {
          "model": "$model",
          "messages": [
              {
                  "role": "user",
                  "content": "$userInput"
              }
          ],
          "temperature": 0.7
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://routerai.ru/api/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val timeTaken = measureTime {
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    printInfo(response.body())
                } else {
                    println("Ошибка: ${response.statusCode()}")
                    println(response.body())

                }
            } catch (e: Exception) {
                println("Ошибка при отправке запроса: ${e.message}")
            }
        }
        println("время ответа: ${timeTaken.inWholeSeconds} секунд")
    }
}

fun printInfo(jsonString: String) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    val response = json.decodeFromString<ChatCompletionResponse>(jsonString)
    println("Model: ${response.model}")
    println("Content: ${response.choices[0].message.content}")
    println("Total tokens: ${response.usage.totalTokens}")
}
