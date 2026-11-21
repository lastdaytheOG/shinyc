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
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.VaultStrings

/**
 * Where a password is typed. Its value is held in plain [remember], so it does not go into the
 * saved state of the screen, and it is cleared once it has been handed over.
 */
@Composable
internal fun PdfPasswordField(buttonText: String, lang: AppLanguage, onPassword: (String) -> Unit) {
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
            label = { Text(VaultStrings.passwordLabel(lang)) },
            singleLine = true,
            visualTransformation = if (showing) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                TextButton(onClick = { showing = !showing }) {
                    Text(if (showing) VaultStrings.hide(lang) else VaultStrings.show(lang))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            VaultStrings.passwordHint(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { submit() }, enabled = password.isNotEmpty(), shape = RoundedCornerShape(12.dp)) {
            Text(buttonText)
        }
    }
}
