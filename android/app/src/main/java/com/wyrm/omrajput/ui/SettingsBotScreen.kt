package com.wyrm.omrajput.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.Setting
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings › AI Trainer.
 *
 * The trainer is currently a deterministic local path evaluator, so this
 * backup preserves its bot settings. The versioned format can be extended
 * with persistent learning data when the trainer gains an adaptive model.
 */
@Composable
fun SettingsBotScreen(
    settings: List<Setting>,
    insetTop: Dp,
    insetBottom: Dp,
    onBack: () -> Unit,
    onChange: (Setting, List<Float>) -> Unit,
) {
    val context = LocalContext.current
    val mode = settings.named("trainer.ai_mode")
    val circle = settings.named("general.bot_circle")
    val radius = settings.named("general.bot_radius")
    // The engine snapshot can briefly arrive with an empty enum option list
    // while settings are being initialized. LiquidSegmented requires at
    // least two entries, so normalize this screen's enum before composition.
    val rows = listOfNotNull(mode, circle, radius).map { setting ->
        if (setting.type == com.wyrm.omrajput.data.SettingType.ENUM && setting.options.size < 2) {
            setting.copy(options = listOf("Bot clássico", "Treinador IA"))
        } else {
            setting
        }
    }
    var backupMessage by remember { mutableStateOf("O backup guarda as configurações do treinador neste dispositivo.") }

    val exportBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            backupMessage = "Criação do backup cancelada."
        } else {
            runCatching {
                val entries = JSONArray()
                rows.forEach { setting ->
                    entries.put(
                        JSONObject()
                            .put("id", setting.id)
                            .put("raw", setting.raw),
                    )
                }
                val payload = JSONObject()
                    .put("format", "wyrm-ai-trainer-backup")
                    .put("version", 1)
                    .put("createdAt", System.currentTimeMillis())
                    .put("settings", entries)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(payload.toString(2))
                } ?: error("Não foi possível gravar o arquivo.")
            }.onSuccess {
                backupMessage = "Backup do Treinador IA salvo com sucesso."
            }.onFailure {
                backupMessage = "Não foi possível salvar o backup: ${it.localizedMessage ?: "erro de arquivo"}"
            }
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            backupMessage = "Restauração cancelada."
        } else {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    JSONObject(it.readText())
                } ?: error("Não foi possível ler o arquivo.")
                require(json.optString("format") == "wyrm-ai-trainer-backup") {
                    "Este arquivo não é um backup do Treinador IA."
                }
                require(json.optInt("version") == 1) { "Versão de backup não compatível." }
                val saved = json.getJSONArray("settings")
                val current = rows.associateBy { it.id }
                var restored = 0
                for (index in 0 until saved.length()) {
                    val entry = saved.getJSONObject(index)
                    val setting = current[entry.getString("id")] ?: continue
                    val values = entry.getString("raw").split(",")
                        .mapNotNull { it.trim().toFloatOrNull() }
                    if (values.isNotEmpty()) {
                        onChange(setting, values)
                        restored++
                    }
                }
                restored
            }.onSuccess { count ->
                backupMessage = if (count > 0) {
                    "${count} configuração(ões) do treinador restaurada(s)."
                } else {
                    "O backup não contém configurações compatíveis com esta versão."
                }
            }.onFailure {
                backupMessage = "Backup inválido ou não foi possível restaurar: ${it.localizedMessage ?: "erro de arquivo"}"
            }
        }
    }

    SettingsDrillScaffold(
        title = "Treinador IA",
        insetTop = insetTop,
        insetBottom = insetBottom,
        onBack = onBack,
    ) {
        Spacer(Modifier.height(18.dp))
        SettingsCard {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 15.dp, bottom = 15.dp)) {
                Text(
                    text = "Treinador IA local",
                    fontFamily = Wyrm.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Wyrm.Ink,
                )
                Text(
                    text = "Escolha entre o Bot clássico e o Treinador IA original do Wyrm. O treinador avalia rotas, procura comida e evita cobras, o próprio corpo e as bordas.",
                    fontFamily = Wyrm.Body,
                    fontSize = 12.5.sp,
                    color = Wyrm.Quiet,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        SettingsSectionLabel("Escolher IA")
        SettingsCard {
            rows.forEachIndexed { index, setting ->
                SettingTypedRow(setting = setting, first = index == 0, onChange = onChange)
            }
        }

        SettingsCaption("Durante a partida, use o botão Treinador IA na tela do jogo para ligar ou desligar o controle automático. O modo escolhido acima define qual IA conduz a cobra.")

        SettingsSectionLabel("Backup do Treinador IA")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = "Salve as configurações do treinador em um arquivo JSON e restaure-as depois, inclusive após reinstalar o APK ou trocar de aparelho.",
                    fontFamily = Wyrm.Body,
                    fontSize = 12.5.sp,
                    color = Wyrm.Quiet,
                )
                Spacer(Modifier.height(12.dp))
                PaperPrimaryButton(
                    label = "Criar backup",
                    onClick = { exportBackup.launch("wyrm-treinador-ia-backup.json") },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    title = "Restaurar backup",
                    first = true,
                    onClick = { importBackup.launch(arrayOf("application/json", "text/*")) },
                )
                Text(
                    text = backupMessage,
                    fontFamily = Wyrm.Body,
                    fontSize = 11.5.sp,
                    color = Wyrm.Quiet,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        SettingsCaption("O treinador atual ainda não aprende entre partidas; esta versão do backup salva as configurações. Os dados de aprendizado poderão ser incluídos quando o sistema de aprendizado persistente for implementado.")
    }
}
