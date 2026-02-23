package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val provider: String,
    val model: String,
    val created: Long?,
    val choices: List<Choice>,
    val usage: Usage
)

@Serializable
data class Choice(
    val logprobs: String? = null,
    @SerialName("finish_reason")
    val finishReason: String?,
    @SerialName("native_finish_reason")
    val nativeFinishReason: String?,
    val index: Int,
    val message: Message
)

@Serializable
data class Message(
    val role: String,
    val content: String,
    val refusal: String? = null,
    val reasoning: String? = null
)

@Serializable
data class Usage(
    @SerialName("total_tokens")
    val totalTokens: Int,
    @SerialName("prompt_tokens")
    val promptTokens: Int?,
    @SerialName("prompt_tokens_details")
    val promptTokensDetails: TokenDetails?,
    @SerialName("completion_tokens")
    val completionTokens: Int?,
    @SerialName("completion_tokens_details")
    val completionTokensDetails: CompletionTokenDetails?
)

@Serializable
data class TokenDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Int,
    @SerialName("audio_tokens")
    val audioTokens: Int = 0,
)

@Serializable
data class CompletionTokenDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int,
    @SerialName("audio_tokens")
    val audioTokens: Int = 0,
)

