package com.snainfotech.tagscout.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.TagScoutLogo
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen

@Composable
fun MfaEnrollScreen(
    state: AuthState,
    onPhoneNumberChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerifyCode: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        TagScoutLogo(size = 56.dp)

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "Set Up Two-Factor Auth", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkText)

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Add a phone number for extra security. You'll receive an SMS code each time you log in.",
            fontSize = 13.sp, color = MediumGray, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (state.error != null) {
            Text(
                text = state.error, color = ErrorRed, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
        }

        if (!state.mfaCodeSent) {
            Text(
                text = "Include country code (e.g. +91 for India)",
                fontSize = 11.sp, color = MediumGray,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )

            OutlinedTextField(
                value = state.mfaPhoneNumber,
                onValueChange = onPhoneNumberChange,
                label = { Text("Phone Number") },
                placeholder = { Text("+91XXXXXXXXXX") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary, unfocusedBorderColor = BorderGray,
                    focusedLabelColor = Primary, cursorColor = Primary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSendCode, enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (state.isLoading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                else Text("Send Verification Code", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(text = "Code sent!", color = SuccessGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp))

            Text(
                text = "Enter the 6-digit code sent to ${state.mfaPhoneNumber}",
                fontSize = 12.sp, color = MediumGray, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = state.mfaSmsCode,
                onValueChange = onSmsCodeChange,
                label = { Text("6-digit Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary, unfocusedBorderColor = BorderGray,
                    focusedLabelColor = Primary, cursorColor = Primary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onVerifyCode,
                enabled = !state.isLoading && state.mfaSmsCode.length >= 6,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (state.isLoading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                else Text("Verify & Enable 2FA", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onSkip) {
            Text(text = "Skip for now", color = MediumGray, fontSize = 13.sp)
        }
    }
}