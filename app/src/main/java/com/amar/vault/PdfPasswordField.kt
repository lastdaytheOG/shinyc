package com.amar.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** The words around the password of a locked PDF. */
internal object LockedPdfWords {
    const val HINT = "Capitals matter. For e-Aadhaar it is the first 4 letters of your name in capitals, then your birth year."

    /** Said where a password is typed to read a file: what becomes of the password, and of the text. */
    const val WHAT_IS_KEPT =
        "The password is not kept. The text read from this file stays in your vault, " +
            "where anyone who opens this app can search it."

    const val OTHERS_TOO = "If it opens this file, it is tried on your other locked files too."

    const val VIEWER_LOCKED ="This PDF is locked. Type its password to open it. The password is not kept."
    const val VIEWER_WRONG = "That password did not open it."
}

/**
 * Where a password is typed. Its value is held in plain [remember], so it does not go into the
 * saved state of the screen, and it is cleared once it has been handed over.
 */
@Composable
internal fun PdfPasswordField(buttonText: String, onPassword: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var showing by remember { mutableStateOf(false) }
    val submit = {
        if (password.isNotEmpty()) {
            onPassword(password)
            password = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showing) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                TextButton(onClick = { showing = !showing }) { Text(if (showing) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            LockedPdfWords.HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { submit() }, enabled = password.isNotEmpty(), shape = RoundedCornerShape(12.dp)) {
            Text(buttonText)
        }
    }
}
