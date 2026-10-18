package io.wadspaces.core.firebase

/** Answers requests from a function, and keeps what was asked. */
class FakeHttp(private val answer: (HttpRequest) -> HttpResponse) : Http {
    val asked = mutableListOf<HttpRequest>()

    override suspend fun send(r: HttpRequest): HttpResponse {
        asked += r
        return answer(r)
    }
}

class MemoryStore(var text: String? = null) : SessionStore {
    override fun load() = text
    override fun save(text: String?) {
        this.text = text
    }
}

val config = FirebaseConfig(apiKey = "key", projectId = "demo", authDomain = "demo.firebaseapp.com")
