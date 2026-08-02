package com.nmtuong.telegramdrive.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmtuong.telegramdrive.R
import com.nmtuong.telegramdrive.domain.*

@Composable
fun AuthorizationScreen(viewModel: AuthorizationViewModel) {
  val session by viewModel.state.collectAsStateWithLifecycle()
  var input by remember(session.state) { mutableStateOf("") }
  var changePhoneVisible by remember(session.state) { mutableStateOf(false) }
  var changePhoneInput by remember(session.state) { mutableStateOf("") }
  var firstName by remember(session.state) { mutableStateOf("") }
  var lastName by remember(session.state) { mutableStateOf("") }
  var acceptedTerms by remember(session.state) { mutableStateOf(false) }
  val state = session.state

  Column(
    Modifier
      .fillMaxSize()
      .safeDrawingPadding()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.headlineMedium)
    when (state) {
      AuthorizationState.MissingConfiguration -> Text(stringResource(R.string.missing_configuration))
      AuthorizationState.Unknown, AuthorizationState.WaitingForTdlibParameters -> Text(stringResource(R.string.initializing))
      AuthorizationState.WaitingForPhoneNumber -> {
        AuthInput(R.string.phone_label, input, false, KeyboardType.Phone) { input = it }
        SubmitButton(session.actionPending, input.isNotBlank()) {
          viewModel.submit(AuthorizationAction.SubmitPhone(input))
          input = ""
        }
        OutlinedButton(
          onClick = { viewModel.submit(AuthorizationAction.RequestQrCode) },
          enabled = !session.actionPending,
          modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.use_other_device)) }
      }
      AuthorizationState.WaitingForCode -> {
        session.codeInfo?.let { CodeInfoText(it) }
        AuthInput(R.string.code_label, input, false, KeyboardType.Text) { input = it }
        SubmitButton(session.actionPending, input.isNotBlank()) {
          viewModel.submit(AuthorizationAction.SubmitCode(input))
          input = ""
        }
        ResendButton(session, viewModel)
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
      }
      is AuthorizationState.WaitingForPassword -> {
        if (state.hint.isNotBlank()) Text(stringResource(R.string.password_hint, state.hint))
        if (state.hasRecoveryEmailAddress && state.recoveryEmailAddressPattern.isNotBlank()) {
          Text(stringResource(R.string.password_recovery_available, state.recoveryEmailAddressPattern))
        }
        AuthInput(R.string.password_label, input, true, KeyboardType.Password) { input = it }
        SubmitButton(session.actionPending, input.isNotBlank()) {
          viewModel.submit(AuthorizationAction.SubmitPassword(input))
          input = ""
        }
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
      }
      AuthorizationState.WaitingForEmailAddress -> {
        AuthInput(R.string.email_label, input, false, KeyboardType.Email) { input = it }
        SubmitButton(session.actionPending, input.isNotBlank()) {
          viewModel.submit(AuthorizationAction.SubmitEmailAddress(input))
          input = ""
        }
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
      }
      AuthorizationState.WaitingForEmailCode -> {
        session.emailCodeInfo?.let {
          if (it.emailAddressPattern.isNotBlank()) Text(stringResource(R.string.email_code_sent_to, it.emailAddressPattern))
        }
        AuthInput(R.string.email_code_label, input, false, KeyboardType.Text) { input = it }
        SubmitButton(session.actionPending, input.isNotBlank()) {
          viewModel.submit(AuthorizationAction.SubmitEmailCode(input))
          input = ""
        }
        ResendButton(session, viewModel)
        if (session.emailCodeInfo?.canResetEmailAddress == true) {
          OutlinedButton(
            onClick = { viewModel.submit(AuthorizationAction.ResetEmailAddress) },
            enabled = !session.actionPending,
            modifier = Modifier.fillMaxWidth(),
          ) { Text(stringResource(R.string.reset_email_address)) }
        }
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
      }
      is AuthorizationState.WaitingForRegistration -> {
        Text(stringResource(R.string.registration_title))
        if (state.terms.text.isNotBlank()) {
          Surface(tonalElevation = 2.dp) {
            Text(state.terms.text, Modifier.padding(12.dp))
          }
        }
        if (state.terms.minimumUserAge > 0) {
          Text(stringResource(R.string.registration_minimum_age, state.terms.minimumUserAge))
        }
        Row(
          Modifier
            .fillMaxWidth()
            .selectable(selected = acceptedTerms, onClick = { acceptedTerms = !acceptedTerms }),
        ) {
          Checkbox(checked = acceptedTerms, onCheckedChange = { acceptedTerms = it })
          Text(stringResource(R.string.accept_terms), Modifier.padding(start = 8.dp, top = 12.dp))
        }
        AuthInput(R.string.first_name_label, firstName, false, KeyboardType.Text) { firstName = it }
        AuthInput(R.string.last_name_label, lastName, false, KeyboardType.Text) { lastName = it }
        SubmitButton(session.actionPending, firstName.trim().isNotEmpty() && acceptedTerms) {
          viewModel.submit(AuthorizationAction.SubmitRegistration(firstName, lastName, acceptedTerms))
        }
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
      }
      is AuthorizationState.WaitingForPremiumPurchase -> {
        Text(stringResource(R.string.premium_required))
        if (state.supportEmailAddress.isNotBlank()) {
          Text(stringResource(R.string.premium_support, state.supportEmailAddress))
        }
        Text(stringResource(R.string.premium_recovery_hint))
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
        ResetButton(session.actionPending, viewModel)
      }
      is AuthorizationState.WaitingForOtherDevice -> {
        Text(stringResource(R.string.other_device))
        SelectionContainer { Text(state.link) }
        Text(stringResource(R.string.other_device_recovery_hint))
        ChangePhoneSection(
          session.actionPending,
          changePhoneVisible,
          changePhoneInput,
          onToggle = { changePhoneVisible = !changePhoneVisible },
          onInput = { changePhoneInput = it },
          onSubmit = {
            viewModel.submit(AuthorizationAction.ChangePhone(changePhoneInput))
            changePhoneInput = ""
            changePhoneVisible = false
          },
        )
        ResetButton(session.actionPending, viewModel)
      }
      AuthorizationState.LoggingOut, AuthorizationState.Closing -> Text(stringResource(R.string.initializing))
      AuthorizationState.Closed -> Text(stringResource(R.string.session_closed))
      AuthorizationState.Ready -> Unit
      is AuthorizationState.Other -> {
        Text(stringResource(R.string.unsupported_state, state.name))
        ResetButton(session.actionPending, viewModel)
      }
    }
    session.safeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}

@Composable
private fun CodeInfoText(info: AuthenticationCodeInfo) {
  val name = info.type.name.removePrefix("authenticationCodeType")
  val details = buildString {
    append(name)
    if (info.type.length > 0) append(" · ").append(info.type.length)
    info.type.hint?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
  }
  Text(stringResource(R.string.code_delivery, details))
}

@Composable
private fun ResendButton(session: AuthorizationSession, viewModel: AuthorizationViewModel) {
  val available = session.state == AuthorizationState.WaitingForEmailCode || session.codeInfo?.canResend == true
  if (available) {
    TextButton(
      enabled = !session.actionPending,
      onClick = { viewModel.submit(AuthorizationAction.ResendCode) },
    ) { Text(stringResource(R.string.resend_code)) }
  } else if ((session.codeInfo?.timeoutSeconds ?: 0) > 0) {
    Text(stringResource(R.string.resend_wait, session.codeInfo?.timeoutSeconds ?: 0))
  }
}

@Composable
private fun ChangePhoneSection(
  pending: Boolean,
  visible: Boolean,
  value: String,
  onToggle: () -> Unit,
  onInput: (String) -> Unit,
  onSubmit: () -> Unit,
) {
  TextButton(enabled = !pending, onClick = onToggle) { Text(stringResource(R.string.change_phone)) }
  if (visible) {
    AuthInput(R.string.phone_label, value, false, KeyboardType.Phone, onInput)
    SubmitButton(pending, value.isNotBlank(), onSubmit)
  }
}

@Composable
private fun ResetButton(pending: Boolean, viewModel: AuthorizationViewModel) {
  OutlinedButton(enabled = !pending, onClick = { viewModel.submit(AuthorizationAction.Reset) }) {
    Text(stringResource(R.string.reset_sign_in))
  }
}

@Composable
private fun AuthInput(label: Int, value: String, secret: Boolean, type: KeyboardType, onValue: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValue,
    label = { Text(stringResource(label)) },
    singleLine = true,
    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions = KeyboardOptions(keyboardType = type),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun SubmitButton(pending: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = enabled && !pending) { Text(stringResource(R.string.submit)) }
}
