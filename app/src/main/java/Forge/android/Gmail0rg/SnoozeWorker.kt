package Forge.android.Gmail0rg

import android.accounts.Account
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val messageId = inputData.getString("messageId") ?: return Result.failure()
        val accountEmail = inputData.getString("accountEmail") ?: return Result.failure()
        return try {
            val androidAccount = Account(accountEmail, "com.google")
            val token = GoogleAuthUtil.getToken(
                applicationContext, androidAccount, "oauth2:https://www.googleapis.com/auth/gmail.modify"
            )
            val client = OkHttpClient()
            val body = JSONObject().apply { put("addLabelIds", listOf("INBOX")) }
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://www.googleapis.com/gmail/v1/users/me/messages/$messageId/modify")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute()
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}