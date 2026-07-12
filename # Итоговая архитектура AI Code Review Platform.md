# Итоговая архитектура AI Code Review Platform

## Review Gateway based architecture (финальная версия)

После всех замечаний архитектура становится более строгой:

* **Gateway — единственный владелец бизнес-логики и состояния**
* **Worker — полностью stateless HTTP-клиент**
* **PostgreSQL — единственный источник истины**
* **llama-server — только inference backend**
* **без Redis / Kafka / LiteLLM / дополнительных брокеров**

Архитектура рассчитана на текущий сценарий:

* 20-30 MR в день;
* длинные LLM-задачи;
* 1–10 LLM backend серверов в будущем;
* локальные модели через llama-server.

---

# 1. Общая схема

```text
                              GitLab
                                 │
                                 │
                         Merge Request Pipeline
                                 │
                                 ▼
                         GitLab CI Job
                                 │
                                 │ POST /reviews
                                 │
                                 ▼
┌───────────────────────────────────────────────────────────────┐
│                    Review Gateway                             │
│                    Spring Boot / Java 21                     │
│                                                               │
│  REST API                                                    │
│                                                               │
│  Review Management                                           │
│  State Machine                                               │
│  Queue Manager                                                │
│  Backend Dispatcher                                           │
│  Retry Manager                                                │
│  Timeout Manager                                              │
│  Deduplication                                                │
│  GitLab Publisher                                              │
│  Metrics / Audit                                               │
│                                                               │
└─────────────────────────┬─────────────────────────────────────┘
                          │
                          │ JDBC
                          ▼
                 ┌──────────────────┐
                 │   PostgreSQL     │
                 │                  │
                 │ Queue            │
                 │ Reviews          │
                 │ Results          │
                 │ Comments         │
                 │ Events           │
                 │ Backends         │
                 └──────────────────┘


                          ▲
                          │
                          │ REST API
                          │
        ┌─────────────────┴─────────────────┐
        │                                   │
        ▼                                   ▼

   Worker #1                           Worker #2

   Mac Mini #1                         Mac Mini #2

   ┌────────────┐                     ┌────────────┐
   │  Worker    │                     │  Worker    │
   └─────┬──────┘                     └─────┬──────┘
         │                                  │
         │ localhost                         │ localhost
         ▼                                  ▼

   llama-server                      llama-server

   Qwen2.5                           Qwen2.5
```

---

# 2. Компоненты системы

---

# 2.1 Review Gateway

## Назначение

Центральный сервис управления AI Review.

Gateway отвечает за:

* прием запросов;
* создание задач;
* управление состояниями;
* распределение задач;
* контроль выполнения;
* публикацию результатов;
* аудит.

---

## Технологии

### Backend

* Java 21
* Spring Boot 3.x
* Spring Web
* Spring Data JPA / JDBC
* Flyway для миграций
* PostgreSQL Driver

---

# 3. API Gateway

## 3.1 API для GitLab CI

### Создание Review

```
POST /reviews
```

Назначение:

Создать задачу AI Review.

Request:

```json
{
  "projectId": 123,
  "mergeRequestId": 45,
  "headSha": "abc123",
  "baseSha": "def456",
  "diff": "...",
  "promptVersion": "v1"
}
```

---

Gateway выполняет:

1. Проверку размера diff.
2. Проверку дедупликации.
3. Сохранение input.
4. Создание job.
5. Возврат reviewId.

Ответ:

```json
{
  "reviewId": 10001,
  "status": "QUEUED"
}
```

---

# Ограничение размера diff

Gateway должен проверять размер входных данных.

Причина:

LLM имеет ограниченный context window.

Например:

```
context = 16384

prompt = 2000

answer reserve = 4000

max diff = 10000 tokens
```

Если размер превышен:

HTTP 422

Ответ:

```json
{
 "error":"DIFF_TOO_LARGE"
}
```

CI публикует понятное сообщение в MR.

---

# 3.2 API Worker

Worker НЕ работает с PostgreSQL.

Worker НЕ знает структуру базы.

Worker взаимодействует только через REST.

---

## Получение задачи

```
POST /jobs/claim
```

Request:

```json
{
 "backendId":"mac-mini-01"
}
```

Gateway внутри выполняет:

```sql
SELECT ...
FOR UPDATE SKIP LOCKED
```

После успешного выбора:

```sql
UPDATE review_jobs
SET
 status='RUNNING',
 worker_id=?,
 backend_id=?
```

Транзакция закрывается.

---

Ответ:

```json
{
 "jobId":500,
 "reviewId":10001,
 "payload":{
    "diff":"..."
 }
}
```

---

# Heartbeat

```
POST /jobs/{id}/heartbeat
```

Worker отправляет каждые ~60 секунд.

Request:

```json
{
 "workerId":"mac-mini-01"
}
```

Ответ:

Продолжать:

```json
{
 "continue":true
}
```

Остановить:

```json
{
 "continue":false
}
```

Причины:

* Review стал OBSOLETE;
* Review отменён;
* Backend больше не используется.

Worker:

```
continue=false

↓

закрывает llama запрос

↓

берет следующую задачу
```

---

# Передача результата

```
POST /jobs/{id}/result
```

Request:

```json
{
 "rawResponse":"...",
 "tokens":12000,
 "duration":900000
}
```

Gateway:

* сохраняет результат;
* парсит комментарии;
* меняет состояние.

---

## Идемпотентность

Если задача уже не RUNNING:

например:

```
COMPLETED
PUBLISHED
FAILED
OBSOLETE
```

Gateway отвечает:

```
200 OK
```

и игнорирует повтор.

---

# 4. State Machine

## Состояния Review

```
NEW

 |
 v

QUEUED

 |
 v

RUNNING

 |
 +------------+
 |            |
 v            v

COMPLETED    FAILED

 |
 v

PUBLISHED
```

Дополнительные:

```
OBSOLETE

CANCELLED
```

---

# Правила переходов

## QUEUED → RUNNING

Только Gateway после claim.

---

## RUNNING → COMPLETED

После успешного ответа Worker.

---

## RUNNING → FAILED

Если:

* превышен retry limit;
* ошибка LLM;
* ошибка обработки результата.

---

## Любое незавершенное состояние → OBSOLETE

Если появился новый head_sha.

---

# 5. Дедупликация

Ключ:

```
project_id

merge_request_id

head_sha
```

Новый Review НЕ создается если существует:

```
NEW
QUEUED
RUNNING
COMPLETED
PUBLISHED
```

Создание нового возможно только если предыдущий:

```
FAILED

CANCELLED

OBSOLETE
```

---

# 6. Queue Manager

Очередь реализуется PostgreSQL.

Без:

* Redis;
* Kafka;
* RabbitMQ.

---

## Выбор задач

Критерии:

```sql
ORDER BY

priority DESC,

created_at ASC
```

---

## Приоритеты

Например:

```
100 Release

50 Critical

10 Normal
```

---

# 7. Worker

Worker максимально простой.

## Ответственность:

* запросить Job;
* вызвать llama-server;
* отправить heartbeat;
* отправить результат.

---

Worker НЕ отвечает за:

* очередь;
* retry;
* GitLab;
* состояние Review;
* хранение данных.

---

Развертывание:

```
Mac Mini

├── llama-server

└── review-worker
```

---

# 8. Backend Management

Таблица:

```
backends
```

Поля:

```
id

name

url

model

capacity

status

last_seen
```

Статусы:

```
ACTIVE

SUSPECT

MAINTENANCE

OFFLINE
```

---

## Правила

Если backend:

* не отвечает;
* падает health-check;

то:

```
ACTIVE

↓

SUSPECT
```

Новые задачи туда не назначаются.

---

# 9. Retry / Timeout

Retry находится только в Gateway.

Worker ничего не знает.

---

Retry выполняется:

например:

```
attempts < 3
```

---

Причины:

* timeout;
* ошибка llama-server;
* сетевой сбой.

---

# Heartbeat контроль

Gateway проверяет:

```
heartbeat_at
```

Если:

```
now - heartbeat_at > 3 минуты
```

задача считается зависшей.

---

# 10. PostgreSQL модель

## review_inputs

Хранит исходные данные.

```
id

review_id

head_sha

base_sha

diff

prompt_version

created_at
```

Данные immutable.

---

## review_jobs

Очередь.

```
id

review_id

status

priority

backend_id

worker_id

attempts

heartbeat_at

created_at

started_at

finished_at
```

---

## review_results

```
id

review_id

raw_response

summary

tokens

duration

model
```

Важно:

`raw_response` обязателен.

---

## review_comments

```
id

review_id

file

line

severity

comment

discussion_id

published_at
```

Используется для идемпотентной публикации.

---

## review_events

Аудит.

```
id

review_id

event

timestamp

worker

backend

details
```

Примеры:

```
CREATED

CLAIMED

RUNNING

HEARTBEAT

RETRY

COMPLETED

PUBLISHED

FAILED

OBSOLETE
```

---

# 11. GitLab Publisher

Gateway публикует комментарии самостоятельно.

Worker не знает GitLab.

---

Особенности:

* публикация идемпотентная;
* используется discussion_id;
* повторная отправка не создает дублей.

---

# 12. Безопасность

## CI → Gateway

Используется:

```
Bearer Token
```

---

## Gateway → GitLab

GitLab API Token хранится только в Gateway.

---

## Worker

Не имеет:

* GitLab token;
* PostgreSQL credentials.

---

# 13. Мониторинг и статистика

Без отдельного Prometheus/Grafana.

Gateway собирает:

* количество Review;
* среднее время ожидания;
* среднее время генерации;
* ошибки;
* retry;
* загрузку Backend;
* количество комментариев.

Источник:

```
review_events
review_results
review_jobs
```

---

# 14. Эксплуатация

## Gateway

Один экземпляр:

```
systemd service
```

Restart:

```
always
```

---

## PostgreSQL Backup

Обязательно:

```
pg_dump
```

по расписанию.

---

# 15. Что сознательно НЕ используется

| Компонент  | Причина                               |
| ---------- | ------------------------------------- |
| Redis      | PostgreSQL покрывает текущую нагрузку |
| Kafka      | Нет необходимости в event streaming   |
| RabbitMQ   | Нет сложной очереди сообщений         |
| LiteLLM    | Нет нескольких моделей/провайдеров    |
| Celery     | Java stack и простой Worker           |
| Prometheus | Пока достаточно встроенной статистики |
| Kubernetes | Нет необходимости в orchestration     |

---

# Финальная модель ответственности

| Компонент    | Ответственность            |
| ------------ | -------------------------- |
| GitLab CI    | отправить запрос           |
| Gateway      | вся бизнес-логика          |
| PostgreSQL   | состояние и история        |
| Worker       | выполнить LLM задачу       |
| llama-server | генерация текста           |
| GitLab API   | публикация MR комментариев |

---

## Итог

Эта версия архитектуры является финальной для реализации MVP/Production v1.

Она закрывает:

✅ retry
✅ восстановление после падения
✅ дедупликацию
✅ отмену устаревших задач
✅ аудит
✅ статистику
✅ идемпотентность
✅ масштабирование до N LLM backend
✅ отсутствие лишней инфраструктуры

При этом архитектура остаётся достаточно простой, чтобы её можно было реализовать небольшой Java-командой и эксплуатировать без отдельной платформенной команды.
