package org.example


import kotlinx.serialization.json.Json
import org.example.Secret.API_KEY
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

fun main() {
    val apiKey = API_KEY // Замените на ваш API-ключ
    val model = "deepseek/deepseek-chat-v3.1"//"openai/gpt-4o-mini" // Или любая другая модель из OpenRouter
    //val client = HttpClient.newBuilder().build()
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
          "stop":[] 
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://routerai.ru/api/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

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
}

fun printInfo(jsonString: String) {
    val json = Json { ignoreUnknownKeys = true }
    val response = json.decodeFromString<ChatCompletionResponse>(jsonString)
    println("Model: ${response.model}")
    println("Content: ${response.choices[0].message.content}")
    println("Total tokens: ${response.usage.totalTokens}")
}
