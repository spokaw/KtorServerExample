package com.example.ktorserverexample

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.slf4j.event.Level

fun main(args: Array<String>) {
    embeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>(
        factory = Netty,
        environment = applicationEngineEnvironment {
            config = HoconApplicationConfig(ConfigFactory.load())
            module {
                module()
            }
            connector {
                port = config.property("ktor.deployment.port").getString().toInt()
                host = config.property("ktor.deployment.host").getString()
            }
        }
    ).start(wait = true)
}

fun Application.module() {
    // Настройка подключения к БД
    val dbConfig = environment.config.config("ktor.database")
    val jdbcUrl = dbConfig.property("jdbcUrl").getString()
    val username = dbConfig.property("username").getString()
    val password = dbConfig.property("password").getString()
    val poolSize = dbConfig.property("poolSize").getString().toInt()

    val hikariConfig = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        maximumPoolSize = poolSize
        driverClassName = "org.postgresql.Driver"
    }

    val dataSource = HikariDataSource(hikariConfig)
    Database.connect(dataSource)

    // Установка плагинов
    install(ContentNegotiation) {
        json()
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error")
            )
        }
    }

    // Маршрутизация
    routing {
        get("/") {
            call.respondText("Server is running! ✅")
        }

        route("/api/users") {
            // Регистрация нового пользователя
            post("/register") {
                val request = call.receive<RegisterRequest>()

                // Валидация
                if (request.username.isBlank() || request.email.isBlank() || request.password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "All fields are required"))
                    return@post
                }

                val passwordHash = hashPassword(request.password)

                try {
                    val userId = transaction {
                        Users.insert { table ->
                            table[Users.username] = request.username
                            table[Users.email] = request.email
                            table[Users.passwordHash] = passwordHash
                            table[Users.fullName] = request.fullName
                            table[Users.createdAt] = Instant.now()
                            table[Users.isActive] = true
                        }[Users.id]
                    }

                    call.respond(HttpStatusCode.Created, mapOf("id" to userId))
                } catch (e: Exception) {
                    call.application.log.error("Registration error", e)

                    when {
                        e.message?.contains("duplicate key value") == true -> {
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Username or email already exists"))
                        }
                        else -> {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Registration failed"))
                        }
                    }
                }
            }

            // Аутентификация пользователя
            post("/login") {
                val credentials = call.receive<LoginRequest>()

                val user = try {
                    transaction {
                        Users.select { Users.username eq credentials.username }
                            .singleOrNull()
                    }
                } catch (e: Exception) {
                    call.application.log.error("Login error", e)
                    null
                }

                if (user == null || !verifyPassword(credentials.password, user[Users.passwordHash])) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                    return@post
                }

                call.respond(user.toUserResponse())
            }

            // Получение информации о пользователе
            get("/{id}") {
                val userId = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))

                val user = try {
                    transaction {
                        Users.select { Users.id eq userId }.singleOrNull()
                    }?.toUserResponse()
                } catch (e: Exception) {
                    call.application.log.error("User fetch error", e)
                    null
                }

                user?.let { call.respond(it) }
                    ?: call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
            }
        }
    }
}

// Хеширование паролей
fun hashPassword(password: String): String {
    return BCrypt.withDefaults().hashToString(12, password.toCharArray())
}

fun verifyPassword(password: String, hash: String): Boolean {
    return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}

// Модель таблицы (соответствует вашей структуре в PostgreSQL)
object Users : Table("users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val username: Column<String> = varchar("username", 50)
    val email: Column<String> = varchar("email", 100)
    val passwordHash: Column<String> = varchar("password_hash", 255)
    val createdAt: Column<Instant> = timestamp("created_at")
    val fullName: Column<String?> = varchar("full_name", 100).nullable()
    val isActive: Column<Boolean> = bool("is_active")

    override val primaryKey = PrimaryKey(id, name = "PK_Users_Id")
}

// DTO-модели
@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val fullName: String? = null
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class UserResponse(
    val id: Int,
    val username: String,
    val email: String,
    val fullName: String?,
    val createdAt: String,
    val isActive: Boolean
)

// Преобразование строки результата в DTO
fun ResultRow.toUserResponse(): UserResponse = UserResponse(
    id = this[Users.id],
    username = this[Users.username],
    email = this[Users.email],
    fullName = this[Users.fullName],
    createdAt = this[Users.createdAt].toString(),
    isActive = this[Users.isActive]
)