# LLM Worker (Executor) — Техническая спецификация v1

## Назначение

Worker — это максимально легковесный агент, разворачиваемый на сервере рядом с экземпляром `llama-server`.

Worker не содержит бизнес-логики AI Review и выполняет исключительно функции транспорта между Review Gateway и локальной LLM.

Все решения (очередь, выбор backend, повторные попытки, таймауты, аудит, публикация результатов) принимаются Review Gateway.

---

# Основные требования

## Функциональность

### Worker должен:

- Зарегистрироваться в Review Gateway.
- Сообщить о своей готовности принимать задачи.
- Запросить следующую задачу.
- Отправить запрос в локальный `llama-server`.
- Получить ответ модели.
- Передать результат обратно в Review Gateway.
- Повторить цикл.

### Worker не должен:

- Работать с PostgreSQL.
- Работать с GitLab API.
- Читать Git-репозитории.
- Управлять очередью.
- Выполнять Retry.
- Выполнять дедупликацию.
- Выполнять маршрутизацию задач.
- Анализировать содержимое prompt.
- Принимать какие-либо бизнес-решения.

---

# Архитектура

```text
+----------------------+
|  Review Gateway      |
+----------+-----------+
           ^
           |
        HTTPS
           |
+----------+-----------+
|      Worker          |
+----------+-----------+
           |
      localhost
           |
+----------+-----------+
|    llama-server      |
+----------------------+
```

Worker имеет только два внешних соединения:

- HTTPS → Review Gateway
- HTTP → localhost:8000 (`llama-server`)

Других зависимостей быть не должно.

---

# Жизненный цикл

```text
load config
      │
      ▼
register
      │
      ▼
──────────── LOOP ────────────
      │
      ▼
request next job
      │
      ├──────────────┐
      │              │
      ▼              ▼
нет задачи        есть задача
      │              │
      ▼              ▼
sleep        POST /chat/completions
      │              │
      ▼              ▼
loop      отправить результат
                     │
                     ▼
                    loop
```

Worker работает непрерывно и самостоятельно не завершается.

---

# Взаимодействие с Review Gateway

## 1. Регистрация

### Запрос

`POST /internal/workers/register`

```json
{
  "workerId": "mac-mini-01",
  "backendId": "qwen-main",
  "hostname": "mac-mini-01",
  "version": "1.0.0",
  "capabilities": {
    "contextWindow": 16384,
    "parallel": 1
  }
}
```

### Ответ

```http
200 OK
```

---

## 2. Получение задачи

### Запрос

`POST /internal/workers/next`

```json
{
  "workerId": "mac-mini-01"
}
```

### Ответ

```json
{
  "jobId": 154,
  "model": "qwen2.5-coder",
  "temperature": 0.1,
  "maxTokens": 4096,
  "messages": [
    ...
  ]
}
```

Если задач нет:

```http
204 No Content
```

---

## 3. Отправка результата

### Запрос

`POST /internal/jobs/{id}/complete`

```json
{
  "finishReason": "stop",
  "promptTokens": 4120,
  "completionTokens": 1860,
  "durationMs": 842311,
  "response": "..."
}
```

---

## 4. Сообщение об ошибке

### Запрос

`POST /internal/jobs/{id}/failed`

```json
{
  "errorType": "TIMEOUT",
  "message": "Read timeout"
}
```

Retry выполняет исключительно Review Gateway.

---

# Взаимодействие с llama-server

Worker использует OpenAI Compatible API.

### Запрос

`POST /v1/chat/completions`

```json
{
  "model": "qwen2.5-coder",
  "messages": [...],
  "temperature": 0.1,
  "max_tokens": 4096
}
```

Ответ передается Gateway без анализа и изменения содержимого.

---

# Heartbeat

Каждые **30 секунд** Worker отправляет heartbeat.

### Запрос

`POST /internal/workers/heartbeat`

Когда Worker свободен:

```json
{
  "workerId": "mac-mini-01",
  "status": "IDLE",
  "runningJob": null,
  "uptime": 86400,
  "version": "1.0.0"
}
```

Когда Worker выполняет задачу:

```json
{
  "workerId": "mac-mini-01",
  "status": "BUSY",
  "runningJob": 154,
  "uptime": 86400,
  "version": "1.0.0"
}
```

Heartbeat используется Gateway для:

- контроля доступности Worker;
- отображения состояния;
- обнаружения зависших Worker;
- автоматического восстановления задач.

---

# Конфигурация

```yaml
gateway:
  url: https://review.company.local
  apiKey: xxx

worker:
  id: mac-mini-01

backend:
  id: qwen-main

llama:
  url: http://127.0.0.1:8000

network:
  pollIntervalMs: 3000
  requestTimeoutSec: 1800

heartbeat:
  intervalSec: 30
```

---

# Логирование

Worker пишет только технические события.

Примеры:

```
Worker started
Connected to Gateway
Job accepted
Job completed
Job failed
Gateway unavailable
Llama timeout
Heartbeat sent
```

Worker не хранит историю Review.

---

# Метрики

Worker предоставляет endpoint:

```
GET /metrics
```

в формате Prometheus.

Минимальный набор метрик:

- `worker_jobs_total`
- `worker_jobs_completed_total`
- `worker_jobs_failed_total`
- `worker_llama_duration_seconds`
- `worker_gateway_errors_total`
- `worker_uptime_seconds`

---

# Требования к производительности

Worker должен:

- работать в одном процессе;
- использовать один HTTP-клиент;
- не использовать встроенную БД;
- не использовать ORM;
- не создавать временные файлы;
- потреблять менее **100 МБ RAM**;
- запускаться менее чем за **2 секунды**;
- не требовать GPU.

---

# Отказоустойчивость

## Недоступен Gateway

Worker:

- продолжает работать;
- периодически пытается переподключиться;
- не завершает процесс.

## Недоступен llama-server

Worker:

- сообщает Gateway об ошибке выполнения задачи;
- переходит в ожидание следующей задачи.

---

# Что НЕ входит в Worker

- Очередь задач.
- Планировщик.
- Retry.
- Rate Limiting.
- Балансировка нагрузки.
- Выбор модели.
- Prompt Manager.
- GitLab API.
- PostgreSQL.
- Redis.
- LiteLLM.
- Кэширование.
- Любая бизнес-логика Code Review.

---

# Архитектурный принцип

Worker — полностью stateless-компонент.

Он:

- не принимает решений;
- не хранит состояние;
- не знает бизнес-контекст;
- не зависит от внутренней реализации Review Gateway.

Его единственная задача — безопасно и максимально быстро доставить запрос от Review Gateway к локальной LLM и вернуть результат обратно.