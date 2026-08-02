package com.nmtuong.telegramdrive

import com.nmtuong.telegramdrive.domain.AuthorizationState
import com.nmtuong.telegramdrive.telegram.TdLibStateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TdLibStateMapperTest {
  @Test fun mapsFirstAuthorizationStateWithoutGeneratedModels() {
    val json = "{\"@type\":\"authorizationStateWaitTdlibParameters\",\"@extra\":\"phase-0\"}"
    assertEquals(AuthorizationState.WaitingForTdlibParameters, TdLibStateMapper.authorizationState(json))
  }
  @Test fun ignoresUnrelatedUpdates() = assertNull(TdLibStateMapper.authorizationState("{\"@type\":\"ok\"}"))
  @Test fun mapsNestedAuthorizationStateDetails() {
    val password = """{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitPassword","password_hint":"pet"}}"""
    val otherDevice = """{"@type":"authorizationStateWaitOtherDeviceConfirmation","link":"tg://login?token=safe-placeholder"}"""
    assertEquals(AuthorizationState.WaitingForPassword("pet"), TdLibStateMapper.authorizationState(password))
    assertEquals(AuthorizationState.WaitingForOtherDevice("tg://login?token=safe-placeholder"), TdLibStateMapper.authorizationState(otherDevice))
  }

  @Test fun mapsAllPinnedLoginStatesAndCodeMetadata() {
    val code = """
      {"@type":"authorizationStateWaitCode","code_info":{
        "phone_number":"+10000000000",
        "type":{"@type":"authenticationCodeTypeSmsWord","first_letter":"A"},
        "next_type":{"@type":"authenticationCodeTypeSms","length":5},
        "timeout":0
      }}
    """.trimIndent()
    val registration = """
      {"@type":"authorizationStateWaitRegistration","terms_of_service":{
        "id":"terms-v1","text":{"@type":"formattedText","text":"Terms text"},
        "min_user_age":18,"show_popup":true
      }}
    """.trimIndent()
    val premium = """
      {"@type":"authorizationStateWaitPremiumPurchase","store_product_id":"telegram-premium",
       "premium_day_count":30,"support_email_address":"support@example.com","support_email_subject":"Premium"}
    """.trimIndent()

    val codeSnapshot = TdLibStateMapper.authorizationSnapshot(code)!!
    assertEquals(AuthorizationState.WaitingForCode, codeSnapshot.state)
    assertEquals("+10000000000", codeSnapshot.codeInfo?.phoneNumber)
    assertEquals("authenticationCodeTypeSmsWord", codeSnapshot.codeInfo?.type?.name)
    assertEquals(true, codeSnapshot.codeInfo?.canResend)

    val registrationState = TdLibStateMapper.authorizationState(registration) as AuthorizationState.WaitingForRegistration
    assertEquals("terms-v1", registrationState.terms.id)
    assertEquals("Terms text", registrationState.terms.text)

    val premiumState = TdLibStateMapper.authorizationState(premium) as AuthorizationState.WaitingForPremiumPurchase
    assertEquals("telegram-premium", premiumState.storeProductId)
  }

  @Test fun mapsEmailCodeMetadataAndPasswordRecoveryMetadata() {
    val email = """
      {"@type":"authorizationStateWaitEmailCode","code_info":{
        "email_address_pattern":"a***@example.com","length":6,
        "email_address_reset_state":{"@type":"emailAddressResetStateAvailable","wait_period":0}}}
    """.trimIndent()
    val password = """
      {"@type":"authorizationStateWaitPassword","password_hint":"pet",
       "has_recovery_email_address":true,"recovery_email_address_pattern":"a***@example.com"}
    """.trimIndent()

    val emailSnapshot = TdLibStateMapper.authorizationSnapshot(email)!!
    assertEquals(AuthorizationState.WaitingForEmailCode, emailSnapshot.state)
    assertEquals("a***@example.com", emailSnapshot.emailCodeInfo?.emailAddressPattern)
    assertEquals(6, emailSnapshot.emailCodeInfo?.length)
    assertEquals(true, emailSnapshot.emailCodeInfo?.canResetEmailAddress)

    val passwordState = TdLibStateMapper.authorizationState(password) as AuthorizationState.WaitingForPassword
    assertEquals(true, passwordState.hasRecoveryEmailAddress)
    assertEquals("a***@example.com", passwordState.recoveryEmailAddressPattern)
  }
}
