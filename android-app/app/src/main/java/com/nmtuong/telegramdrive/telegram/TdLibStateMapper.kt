package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

object TdLibStateMapper {
  fun authorizationState(json: String): AuthorizationState? {
    return authorizationSnapshot(json)?.state
  }

  fun authorizationSnapshot(json: String): AuthorizationStateSnapshot? {
    val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val state = if (root["@type"]?.jsonPrimitive?.contentOrNull == "updateAuthorizationState") {
      root["authorization_state"]?.jsonObject ?: return null
    } else root
    val name = state["@type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("authorizationState") } ?: return null
    return when (name) {
      "authorizationStateWaitTdlibParameters" -> AuthorizationStateSnapshot(AuthorizationState.WaitingForTdlibParameters)
      "authorizationStateWaitPhoneNumber" -> AuthorizationStateSnapshot(AuthorizationState.WaitingForPhoneNumber)
      "authorizationStateWaitPremiumPurchase" -> AuthorizationStateSnapshot(
        AuthorizationState.WaitingForPremiumPurchase(
          storeProductId = state.text("store_product_id"),
          premiumDayCount = state.number("premium_day_count"),
          supportEmailAddress = state.text("support_email_address"),
          supportEmailSubject = state.text("support_email_subject"),
        ),
      )
      "authorizationStateWaitEmailAddress" -> AuthorizationStateSnapshot(AuthorizationState.WaitingForEmailAddress)
      "authorizationStateWaitEmailCode" -> AuthorizationStateSnapshot(
        state = AuthorizationState.WaitingForEmailCode,
        emailCodeInfo = state.child("code_info")?.let(::emailCodeInfo),
      )
      "authorizationStateWaitCode" -> AuthorizationStateSnapshot(
        state = AuthorizationState.WaitingForCode,
        codeInfo = state.child("code_info")?.let(::authenticationCodeInfo),
      )
      "authorizationStateWaitOtherDeviceConfirmation" -> AuthorizationStateSnapshot(
        AuthorizationState.WaitingForOtherDevice(state.text("link")),
      )
      "authorizationStateWaitRegistration" -> AuthorizationStateSnapshot(
        AuthorizationState.WaitingForRegistration(registrationTerms(state.child("terms_of_service"))),
      )
      "authorizationStateWaitPassword" -> AuthorizationStateSnapshot(
        AuthorizationState.WaitingForPassword(
          hint = state.text("password_hint"),
          hasRecoveryEmailAddress = state.flag("has_recovery_email_address"),
          recoveryEmailAddressPattern = state.text("recovery_email_address_pattern"),
        ),
      )
      "authorizationStateReady" -> AuthorizationStateSnapshot(AuthorizationState.Ready)
      "authorizationStateLoggingOut" -> AuthorizationStateSnapshot(AuthorizationState.LoggingOut)
      "authorizationStateClosing" -> AuthorizationStateSnapshot(AuthorizationState.Closing)
      "authorizationStateClosed" -> AuthorizationStateSnapshot(AuthorizationState.Closed)
      else -> AuthorizationStateSnapshot(AuthorizationState.Other(name))
    }
  }

  private fun authenticationCodeInfo(value: JsonObject): AuthenticationCodeInfo {
    return AuthenticationCodeInfo(
      phoneNumber = value.text("phone_number"),
      type = authenticationCodeType(value.child("type")),
      nextType = value.child("next_type")?.let(::authenticationCodeType),
      timeoutSeconds = value.number("timeout"),
    )
  }

  private fun authenticationCodeType(value: JsonObject?): AuthenticationCodeTypeInfo {
    val name = value?.text("@type") ?: "unknown"
    val hint = value?.text("first_letter") ?: value?.text("first_word")
      ?: value?.text("pattern")
    return AuthenticationCodeTypeInfo(
      name = name,
      length = value?.number("length") ?: 0,
      hint = hint,
    )
  }

  private fun emailCodeInfo(value: JsonObject): EmailAuthenticationCodeInfo {
    val resetState = value.child("email_address_reset_state")
    val resetType = resetState?.text("@type")
    return EmailAuthenticationCodeInfo(
      emailAddressPattern = value.text("email_address_pattern"),
      length = value.number("length"),
      canResetEmailAddress = resetType == "emailAddressResetStateAvailable" || resetType == "emailAddressResetStatePending",
      resetWaitSeconds = resetState?.number("wait_period") ?: resetState?.number("reset_in") ?: 0,
    )
  }

  private fun registrationTerms(value: JsonObject?): RegistrationTerms {
    val formattedText = value?.child("text")
    return RegistrationTerms(
      id = value?.text("id").orEmpty(),
      text = formattedText?.text("text").orEmpty(),
      minimumUserAge = value?.number("min_user_age") ?: 0,
      showPopup = value?.flag("show_popup") == true,
    )
  }
}

private fun JsonObject.text(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.number(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0
private fun JsonObject.flag(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject
