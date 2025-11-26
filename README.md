# Loop AI - Voice-Enabled Hospital Network Assistant 🏥🎤

A conversational AI assistant that helps users find hospitals in their insurance network using voice commands. Built with Spring Boot, LangChain4j, and modern web technologies.

## 🌟 Features

- **Voice Interface**: Speak naturally to find hospitals
- **RAG-Powered Search**: Semantic search over hospital database
- **Multi-City Support**: Handles city name aliases (Bangalore/Bengaluru, Mumbai/Bombay)
- **Clarification Questions**: Asks for city when hospital exists in multiple locations
- **Human Agent Forwarding**: Out-of-scope queries forwarded via Twilio SMS
- **Google OAuth**: Secure authentication with per-user conversation memory

## 🏗️ Architecture

```
User Voice → Deepgram STT → Groq LLM (Llama 3.3) → RAG Tools → OpenAI TTS → Audio Response
```

| Component | Technology | Purpose |
|-----------|------------|---------|
| STT | Deepgram Nova-2 | Speech-to-Text |
| LLM | Groq (Llama 3.3 70B) | Fast AI responses |
| RAG | LangChain4j + CSV | Hospital database search |
| TTS | OpenAI TTS-1 | Text-to-Speech |
| Auth | Google OAuth 2.0 | User authentication |
| Forwarding | Twilio | Human agent escalation |

## 📁 Project Structure

```
LOOP/
├── loop-ai-backend/          # Spring Boot backend
│   ├── src/main/java/
│   │   └── com/loopai/backend/
│   │       ├── controller/   # REST endpoints
│   │       ├── service/      # Business logic
│   │       ├── config/       # Security, CORS
│   │       └── model/        # Data models
│   └── src/main/resources/
│       ├── data/             # Hospital CSV
│       └── application.properties.example
│
└── loop-ai-frontend/         # Static HTML/CSS/JS
    ├── index.html            # Login page
    ├── home.html             # Voice chat interface
    └── intro.mp3             # Intro audio
```
