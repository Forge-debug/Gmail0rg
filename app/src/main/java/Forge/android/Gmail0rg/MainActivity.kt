package Forge.android.Gmail0rg
import android.accounts.Account
import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class EmailItem(val id: String, val account: String, val subject: String)

fun Modifier.onLongPressGesture(onLongPress: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = { onLongPress() })
    }
)

// ---- Simpele opslag voor gekoppelde accounts ----
object AccountStore {
    private const val PREFS_NAME = "gmailorg_prefs"
    private const val KEY_ACCOUNTS = "linked_accounts"

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()
        return stored.toList()
    }

    fun save(context: Context, accounts: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_ACCOUNTS, accounts.toSet()).apply()
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/gmail.modify"))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            MaterialTheme {
                var accountEmails by remember { mutableStateOf(AccountStore.load(this)) }
                var emails by remember { mutableStateOf(listOf<EmailItem>()) }
                var loading by remember { mutableStateOf(false) }
                var showCompose by remember { mutableStateOf(false) }
                var senderQuery by remember { mutableStateOf("") }
                var statusText by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                fun addAccount(email: String) {
                    if (accountEmails.none { it == email }) {
                        accountEmails = accountEmails + email
                        AccountStore.save(this, accountEmails)
                    }
                }

                fun removeAccount(email: String) {
                    accountEmails = accountEmails.filterNot { it == email }
                    AccountStore.save(this, accountEmails)
                    emails = emails.filterNot { it.account == email }
                }

                val signInLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    val newAccount = task.result
                    val email = newAccount?.email
                    if (email != null) addAccount(email)
                }

                fun refreshInbox() {
                    loading = true
                    scope.launch {
                        val results = mutableListOf<EmailItem>()
                        for (email in accountEmails) {
                            results.addAll(fetchInbox(email))
                        }
                        emails = results
                        loading = false
                    }
                }

                Scaffold(
                    floatingActionButton = {
                        if (accountEmails.isNotEmpty()) {
                            FloatingActionButton(onClick = { showCompose = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Nieuwe e-mail")
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                    ) {

                        if (accountEmails.isEmpty()) {
                            Button(onClick = { signInLauncher.launch(googleSignInClient.signInIntent) }) {
                                Text("Inloggen met Google")
                            }
                        } else {
                            Text("Gekoppelde accounts:", fontWeight = FontWeight.Bold)
                            accountEmails.forEach { email ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("• $email", modifier = Modifier.weight(1f))
                                    TextButton(onClick = { removeAccount(email) }) {
                                        Text("Ontkoppelen")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(onClick = {
                                googleSignInClient.signOut().addOnCompleteListener {
                                    signInLauncher.launch(googleSignInClient.signInIntent)
                                }
                            }) {
                                Text("Nog een account toevoegen")
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(onClick = { refreshInbox() }) {
                                Text(if (loading) "Bezig met ophalen..." else "Gecombineerde inbox ophalen")
                            }

                            Text(
                                "Tip: veeg rechts = archiveren, links = verwijderen, lang indrukken = snoozen",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                            )

                            Button(onClick = {
                                scope.launch {
                                    val n = cleanupPromotions(accountEmails)
                                    statusText = "$n promotiemails opgeruimd"
                                    refreshInbox()
                                }
                            }) {
                                Text("Promoties opruimen")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = senderQuery,
                                onValueChange = { senderQuery = it },
                                label = { Text("Verwijder alles van afzender (e-mailadres)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(onClick = {
                                scope.launch {
                                    val n = deleteBySender(accountEmails, senderQuery)
                                    statusText = "$n berichten verwijderd van $senderQuery"
                                    refreshInbox()
                                }
                            }) {
                                Text("Verwijderen op afzender")
                            }

                            if (statusText.isNotEmpty()) {
                                Text(statusText, modifier = Modifier.padding(top = 8.dp))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LazyColumn {
                                items(emails, key = { it.account + it.id }) { item ->
                                    SwipeableEmailRow(
                                        item = item,
                                        onArchive = {
                                            scope.launch { archiveMessage(item) }
                                            emails = emails.filterNot { it.id == item.id && it.account == item.account }
                                        },
                                        onDelete = {
                                            scope.launch { deleteMessage(item) }
                                            emails = emails.filterNot { it.id == item.id && it.account == item.account }
                                        },
                                        onSnooze = { minutes ->
                                            scope.launch {
                                                archiveMessage(item)
                                                scheduleSnooze(item, minutes)
                                            }
                                            emails = emails.filterNot { it.id == item.id && it.account == item.account }
                                        }
                                    )
                                    Divider()
                                }
                            }
                        }
                    }
                }

                if (showCompose) {
                    ComposeDialog(
                        accountEmails = accountEmails,
                        onDismiss = { showCompose = false },
                        onSend = { fromEmail, to, subject, body ->
                            scope.launch {
                                sendMessage(fromEmail, to, subject, body)
                                showCompose = false
                            }
                        }
                    )
                }
            }
        }
    }

    private suspend fun getToken(email: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val androidAccount = Account(email, "com.google")
                GoogleAuthUtil.getToken(
                    this@MainActivity,
                    androidAccount,
                    "oauth2:https://www.googleapis.com/auth/gmail.modify"
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun fetchInbox(email: String): List<EmailItem> {
        return withContext(Dispatchers.IO) {
            try {
                val token = getToken(email) ?: return@withContext listOf(EmailItem("", email, "Kon geen token krijgen"))

                val listRequest = Request.Builder()
                    .url("https://www.googleapis.com/gmail/v1/users/me/messages?maxResults=10")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val listJson = JSONObject(client.newCall(listRequest).execute().body?.string() ?: "{}")
                val messageArray = listJson.optJSONArray("messages")
                    ?: return@withContext listOf(EmailItem("", email, "Geen berichten gevonden"))

                val result = mutableListOf<EmailItem>()
                for (i in 0 until messageArray.length()) {
                    val id = messageArray.getJSONObject(i).getString("id")
                    val msgRequest = Request.Builder()
                        .url("https://www.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata&metadataHeaders=Subject")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    val msgJson = JSONObject(client.newCall(msgRequest).execute().body?.string() ?: "{}")
                    val headers = msgJson.optJSONObject("payload")?.optJSONArray("headers")
                    var subject = "(geen onderwerp)"
                    if (headers != null) {
                        for (j in 0 until headers.length()) {
                            val header = headers.getJSONObject(j)
                            if (header.getString("name") == "Subject") subject = header.getString("value")
                        }
                    }
                    result.add(EmailItem(id, email, subject))
                }
                result
            } catch (e: Exception) {
                listOf(EmailItem("", email, "Fout bij ophalen: ${e.message}"))
            }
        }
    }

    private suspend fun archiveMessage(item: EmailItem) {
        withContext(Dispatchers.IO) {
            try {
                val token = getToken(item.account) ?: return@withContext
                val body = JSONObject().apply {
                    put("removeLabelIds", listOf("INBOX"))
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://www.googleapis.com/gmail/v1/users/me/messages/${item.id}/modify")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(request).execute()
            } catch (_: Exception) { }
        }
    }

    private suspend fun deleteMessage(item: EmailItem) {
        withContext(Dispatchers.IO) {
            try {
                val token = getToken(item.account) ?: return@withContext
                val body = "".toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://www.googleapis.com/gmail/v1/users/me/messages/${item.id}/trash")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(request).execute()
            } catch (_: Exception) { }
        }
    }

    private suspend fun sendMessage(fromEmail: String, to: String, subject: String, bodyText: String) {
        withContext(Dispatchers.IO) {
            try {
                val token = getToken(fromEmail) ?: return@withContext
                val rawEmail = "From: $fromEmail\r\nTo: $to\r\nSubject: $subject\r\n\r\n$bodyText"
                val encoded = Base64.encodeToString(
                    rawEmail.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                val jsonBody = JSONObject().apply { put("raw", encoded) }
                    .toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://www.googleapis.com/gmail/v1/users/me/messages/send")
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody)
                    .build()
                client.newCall(request).execute()
            } catch (_: Exception) { }
        }
    }

    private fun scheduleSnooze(item: EmailItem, delayMinutes: Long) {
        val data = workDataOf(
            "messageId" to item.id,
            "accountEmail" to item.account
        )
        val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(data)
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private suspend fun cleanupPromotions(accountEmails: List<String>): Int {
        var count = 0
        withContext(Dispatchers.IO) {
            for (email in accountEmails) {
                try {
                    val token = getToken(email) ?: continue
                    val listRequest = Request.Builder()
                        .url("https://www.googleapis.com/gmail/v1/users/me/messages?q=category:promotions&maxResults=50")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    val listJson = JSONObject(client.newCall(listRequest).execute().body?.string() ?: "{}")
                    val messageArray = listJson.optJSONArray("messages") ?: continue
                    for (i in 0 until messageArray.length()) {
                        val id = messageArray.getJSONObject(i).getString("id")
                        val body = JSONObject().apply {
                            put("removeLabelIds", listOf("INBOX"))
                        }.toString().toRequestBody("application/json".toMediaType())
                        val modifyRequest = Request.Builder()
                            .url("https://www.googleapis.com/gmail/v1/users/me/messages/$id/modify")
                            .addHeader("Authorization", "Bearer $token")
                            .post(body)
                            .build()
                        client.newCall(modifyRequest).execute()
                        count++
                    }
                } catch (_: Exception) { }
            }
        }
        return count
    }

    private suspend fun deleteBySender(accountEmails: List<String>, sender: String): Int {
        var count = 0
        withContext(Dispatchers.IO) {
            for (email in accountEmails) {
                try {
                    val token = getToken(email) ?: continue
                    val listRequest = Request.Builder()
                        .url("https://www.googleapis.com/gmail/v1/users/me/messages?q=from:$sender&maxResults=50")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    val listJson = JSONObject(client.newCall(listRequest).execute().body?.string() ?: "{}")
                    val messageArray = listJson.optJSONArray("messages") ?: continue
                    for (i in 0 until messageArray.length()) {
                        val id = messageArray.getJSONObject(i).getString("id")
                        val trashBody = "".toRequestBody("application/json".toMediaType())
                        val trashRequest = Request.Builder()
                            .url("https://www.googleapis.com/gmail/v1/users/me/messages/$id/trash")
                            .addHeader("Authorization", "Bearer $token")
                            .post(trashBody)
                            .build()
                        client.newCall(trashRequest).execute()
                        count++
                    }
                } catch (_: Exception) { }
            }
        }
        return count
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEmailRow(
    item: EmailItem,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: (Long) -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onArchive(); true }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                else -> false
            }
        }
    )
    var showSnoozeMenu by remember { mutableStateOf(false) }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val (color, icon, alignment) = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(Color(0xFF4CAF50), Icons.Filled.Archive, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(Color(0xFFE53935), Icons.Filled.Delete, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, null, Alignment.Center)
            }
            Box(
                modifier = Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                icon?.let { Icon(it, contentDescription = null, tint = Color.White) }
            }
        }
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 8.dp)
                    .onLongPressGesture { showSnoozeMenu = true }
            ) {
                Text(item.subject, fontWeight = FontWeight.Bold)
                Text(item.account, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = showSnoozeMenu, onDismissRequest = { showSnoozeMenu = false }) {
                DropdownMenuItem(text = { Text("Snooze 1 uur") }, onClick = { onSnooze(60L); showSnoozeMenu = false })
                DropdownMenuItem(text = { Text("Snooze tot morgen") }, onClick = { onSnooze(1440L); showSnoozeMenu = false })
                DropdownMenuItem(text = { Text("Snooze 1 week") }, onClick = { onSnooze(10080L); showSnoozeMenu = false })
            }
        }
    }
}

@Composable
fun ComposeDialog(
    accountEmails: List<String>,
    onDismiss: () -> Unit,
    onSend: (String, String, String, String) -> Unit
) {
    var selectedEmail by remember { mutableStateOf(accountEmails.firstOrNull() ?: "") }
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nieuwe e-mail") },
        text = {
            Column {
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text("Van: $selectedEmail")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        accountEmails.forEach { email ->
                            DropdownMenuItem(
                                text = { Text(email) },
                                onClick = { selectedEmail = email; expanded = false }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("Aan") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Onderwerp") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Bericht") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSend(selectedEmail, to, subject, body)
            }) { Text("Versturen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleren") }
        }
    )
}