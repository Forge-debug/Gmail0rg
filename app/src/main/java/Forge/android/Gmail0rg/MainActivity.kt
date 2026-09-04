package Forge.android.Gmail0rg
import android.accounts.Account
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class EmailItem(val account: String, val subject: String)

class MainActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/gmail.modify"))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            MaterialTheme {
                var accounts by remember { mutableStateOf(listOf<GoogleSignInAccount>()) }
                var emails by remember { mutableStateOf(listOf<EmailItem>()) }
                var loading by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                val signInLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    val newAccount = task.result
                    if (newAccount != null && accounts.none { it.email == newAccount.email }) {
                        accounts = accounts + newAccount
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                    if (accounts.isEmpty()) {
                        Button(onClick = { signInLauncher.launch(googleSignInClient.signInIntent) }) {
                            Text("Inloggen met Google")
                        }
                    } else {
                        Text("Gekoppelde accounts:", fontWeight = FontWeight.Bold)
                        accounts.forEach { acc ->
                            Text("• ${acc.email}")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row {
                            Button(onClick = {
                                // Forceert een account-kiezer die ook een NIEUW account toont
                                googleSignInClient.signOut().addOnCompleteListener {
                                    signInLauncher.launch(googleSignInClient.signInIntent)
                                }
                            }) {
                                Text("Nog een account toevoegen")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(onClick = {
                            loading = true
                            scope.launch {
                                val results = mutableListOf<EmailItem>()
                                for (acc in accounts) {
                                    results.addAll(fetchInbox(acc))
                                }
                                emails = results
                                loading = false
                            }
                        }) {
                            Text(if (loading) "Bezig met ophalen..." else "Gecombineerde inbox ophalen")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        LazyColumn {
                            items(emails) { item ->
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(item.subject, fontWeight = FontWeight.Bold)
                                    Text(item.account, style = MaterialTheme.typography.bodySmall)
                                }
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchInbox(account: GoogleSignInAccount): List<EmailItem> {
        return withContext(Dispatchers.IO) {
            try {
                val email = account.email ?: return@withContext listOf(
                    EmailItem("onbekend", "Geen e-mailadres gevonden")
                )
                val androidAccount = Account(email, "com.google")
                val token = GoogleAuthUtil.getToken(
                    this@MainActivity,
                    androidAccount,
                    "oauth2:https://www.googleapis.com/auth/gmail.modify"
                )
                val client = OkHttpClient()

                val listRequest = Request.Builder()
                    .url("https://www.googleapis.com/gmail/v1/users/me/messages?maxResults=10")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val listJson = JSONObject(client.newCall(listRequest).execute().body?.string() ?: "{}")
                val messageArray = listJson.optJSONArray("messages")
                    ?: return@withContext listOf(EmailItem(email, "Geen berichten gevonden"))

                val result = mutableListOf<EmailItem>()
                for (i in 0 until messageArray.length()) {
                    val id = messageArray.getJSONObject(i).getString("id")
                    val msgRequest = Request.Builder()
                        .url("https://www.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata&metadataHeaders=Subject")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    val msgJson = JSONObject(client.newCall(msgRequest).execute().body?.string() ?: "{}")
                    val headers = msgJson.getJSONObject("payload").optJSONArray("headers")
                    var subject = "(geen onderwerp)"
                    if (headers != null) {
                        for (j in 0 until headers.length()) {
                            val header = headers.getJSONObject(j)
                            if (header.getString("name") == "Subject") subject = header.getString("value")
                        }
                    }
                    result.add(EmailItem(email, subject))
                }
                result
            } catch (e: Exception) {
                listOf(EmailItem(account.email ?: "onbekend", "Fout bij ophalen: ${e.message}"))
            }
        }
    }
}