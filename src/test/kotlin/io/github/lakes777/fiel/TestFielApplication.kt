package io.github.lakes777.fiel

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/** Sobe a API com um Postgres do Testcontainers, sem precisar do docker compose: ./gradlew bootTestRun */
fun main(args: Array<String>) {
    fromApplication<FielApplication>().with(TestcontainersConfiguration::class).run(*args)
}
