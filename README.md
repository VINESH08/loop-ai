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

## 🚀 Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- Node.js (for frontend server)

### 1. Clone & Configure

```bash
git clone https://github.com/YOUR_USERNAME/loop-ai.git
cd loop-ai

# Copy and configure API keys
cp loop-ai-backend/src/main/resources/application.properties.example \
   loop-ai-backend/src/main/resources/application.properties

# Edit application.properties with your API keys
```

### 2. Get API Keys

| Service | URL | Purpose |
|---------|-----|---------|
| Google OAuth | [console.cloud.google.com](https://console.cloud.google.com/apis/credentials) | Authentication |
| Deepgram | [console.deepgram.com](https://console.deepgram.com/) | Speech-to-Text |
| Groq | [console.groq.com](https://console.groq.com/) | LLM (FREE!) |
| OpenAI | [platform.openai.com](https://platform.openai.com/api-keys) | TTS |
| Twilio | [console.twilio.com](https://console.twilio.com/) | SMS (optional) |

### 3. Add Hospital Data

Place your hospital CSV in:
```
loop-ai-backend/src/main/resources/data/hospitals.csv
```

Required columns: `HOSPITAL NAME`, `Address`, `CITY`

### 4. Run Backend

```bash
cd loop-ai-backend
./mvnw spring-boot:run
```

### 5. Run Frontend

```bash
cd loop-ai-frontend
python3 -m http.server 3000
```

### 6. Open App

Visit: http://localhost:3000

## 📝 Example Queries

- "Tell me 3 hospitals in Bangalore"
- "Can you confirm if Manipal Sarjapur is in my network?"
- "Give me the address of Apollo Hospital"
- "What hospitals are near Whitefield?"

## 🔒 Security Notes

- **NEVER commit `application.properties`** - it contains API keys
- Use `application.properties.example` as a template
- The `.gitignore` already excludes sensitive files

## 📄 License

MIT License - feel free to use and modify!

---

Built with ❤️ using Spring Boot, LangChain4j, Groq, Deepgram, and OpenAI

