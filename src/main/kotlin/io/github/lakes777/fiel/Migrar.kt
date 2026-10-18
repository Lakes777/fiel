package io.github.lakes777.fiel

import org.flywaydb.core.Flyway
import kotlin.system.exitProcess

/**
 * Aplica as migrações com o DONO das tabelas, numa execução à parte, sem subir a API:
 *
 *     java -Dloader.main=io.github.lakes777.fiel.MigrarKt -cp app.jar \
 *         org.springframework.boot.loader.launch.PropertiesLauncher
 *
 * Em produção a API parte com FIEL_MIGRAR_NA_PARTIDA=false e entra com o usuário restrito (V8). Assim
 * a senha do dono só existe durante esta execução, nunca no contêiner da API. Lê do ambiente:
 * FIEL_BANCO_URL, FIEL_BANCO_DONO, FIEL_BANCO_DONO_SENHA e FIEL_BANCO_PAPEL_API (o usuário da API).
 */
fun main() {
    val faltando =
        listOf("FIEL_BANCO_URL", "FIEL_BANCO_DONO", "FIEL_BANCO_DONO_SENHA", "FIEL_BANCO_PAPEL_API")
            .filter { System.getenv(it).isNullOrBlank() }
    if (faltando.isNotEmpty()) {
        System.err.println("Faltam no ambiente: ${faltando.joinToString()}")
        exitProcess(2)
    }
    val ambiente = System.getenv()
    val papel = ambiente.getValue("FIEL_BANCO_PAPEL_API")
    // O nome entra cru no SQL da V8 (placeholder): só letras minúsculas, dígitos e _
    if (!Regex("^[a-z_][a-z0-9_]*$").matches(papel)) {
        System.err.println("FIEL_BANCO_PAPEL_API inválido: use só letras minúsculas, dígitos e _")
        exitProcess(2)
    }
    // A API com o mesmo usuário do dono desfaria o propósito (e a V8 tiraria do dono o histórico)
    if (papel == ambiente["FIEL_BANCO_DONO"]) {
        System.err.println("FIEL_BANCO_PAPEL_API não pode ser o próprio dono")
        exitProcess(2)
    }
    val resultado =
        Flyway
            .configure()
            .dataSource(ambiente["FIEL_BANCO_URL"], ambiente["FIEL_BANCO_DONO"], ambiente["FIEL_BANCO_DONO_SENHA"])
            .placeholders(mapOf("papel_api" to papel))
            .validateOnMigrate(true)
            .load()
            .migrate()
    val versao = resultado.targetSchemaVersion ?: "a mesma"
    println("Migrações aplicadas agora: ${resultado.migrationsExecuted} (versão $versao)")
}
