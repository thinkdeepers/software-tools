package com.cursor.mobile.data.api

import com.cursor.mobile.data.model.AgentsListResponse
import com.cursor.mobile.data.model.ArtifactsResponse
import com.cursor.mobile.data.model.ArtifactDownloadResponse
import com.cursor.mobile.data.model.AgentDetail
import com.cursor.mobile.data.model.ConversationResponse
import com.cursor.mobile.data.model.CreateAgentRequest
import com.cursor.mobile.data.model.CreateAgentResponse
import com.cursor.mobile.data.model.CreateRunRequest
import com.cursor.mobile.data.model.CreateRunResponse
import com.cursor.mobile.data.model.IdResponse
import com.cursor.mobile.data.model.MeResponse
import com.cursor.mobile.data.model.ModelsResponse
import com.cursor.mobile.data.model.RepositoriesResponse
import com.cursor.mobile.data.model.RunSummary
import com.cursor.mobile.data.model.RunsListResponse
import com.cursor.mobile.data.model.UsageResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CursorApi {
    @GET("v1/me")
    suspend fun me(): MeResponse

    @GET("v1/models")
    suspend fun listModels(): ModelsResponse

    @GET("v1/repositories")
    suspend fun listRepositories(): RepositoriesResponse

    @GET("v1/agents")
    suspend fun listAgents(
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
        @Query("includeArchived") includeArchived: Boolean = false
    ): AgentsListResponse

    @GET("v1/agents/{id}")
    suspend fun getAgent(@Path("id") id: String): AgentDetail

    @POST("v1/agents")
    suspend fun createAgent(@Body body: CreateAgentRequest): CreateAgentResponse

    @POST("v1/agents/{id}/runs")
    suspend fun createRun(
        @Path("id") id: String,
        @Body body: CreateRunRequest
    ): CreateRunResponse

    @GET("v1/agents/{id}/runs")
    suspend fun listRuns(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): RunsListResponse

    @GET("v1/agents/{id}/runs/{runId}")
    suspend fun getRun(
        @Path("id") id: String,
        @Path("runId") runId: String
    ): RunSummary

    @POST("v1/agents/{id}/runs/{runId}/cancel")
    suspend fun cancelRun(
        @Path("id") id: String,
        @Path("runId") runId: String
    ): IdResponse

    @POST("v1/agents/{id}/archive")
    suspend fun archiveAgent(@Path("id") id: String): IdResponse

    @POST("v1/agents/{id}/unarchive")
    suspend fun unarchiveAgent(@Path("id") id: String): IdResponse

    @DELETE("v1/agents/{id}")
    suspend fun deleteAgent(@Path("id") id: String): IdResponse

    @GET("v1/agents/{id}/usage")
    suspend fun getUsage(
        @Path("id") id: String,
        @Query("runId") runId: String? = null
    ): UsageResponse

    @GET("v1/agents/{id}/artifacts")
    suspend fun listArtifacts(@Path("id") id: String): ArtifactsResponse

    @GET("v1/agents/{id}/artifacts/download")
    suspend fun downloadArtifact(
        @Path("id") id: String,
        @Query("path") path: String
    ): ArtifactDownloadResponse

    @GET("v0/agents/{id}/conversation")
    suspend fun getConversation(@Path("id") id: String): ConversationResponse
}
