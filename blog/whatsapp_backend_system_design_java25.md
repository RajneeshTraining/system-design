# System Design of WhatsApp: A Production-Oriented Backend PoC with Java 25 and Spring Boot

> **Audience:** Java engineers, senior developers, tech leads,
> architects, and system-design interview candidates\
> **Scope:** Backend only. No mobile/web UI implementation.\
> **Goal:** Build a WhatsApp-like messaging platform that is runnable as
> a PoC and has a credible evolution path toward production.\
> **Important:** This is an independent design, not a description of
> WhatsApp's proprietary internal architecture. It does not claim
> WhatsApp's exact scale, implementation, or security certification.

------------------------------------------------------------------------

## 1. What are we building?

We will design a messaging platform with these capabilities:

-   User registration, authentication, and device management
-   One-to-one conversations
-   Group conversations and membership management
-   Real-time message delivery over persistent connections
-   Offline message storage and delivery after reconnect
-   Sent, delivered, and read receipts
-   Message ordering and idempotent retries
-   Multi-device synchronization
-   Image, video, audio, and document attachments
-   End-to-end encryption (E2EE) architecture
-   Presence and last-seen indicators, with privacy controls
-   Typing indicators
-   Push notifications for offline devices
-   Message history, pagination, retention, and deletion
-   Abuse prevention, rate limits, blocking, and reporting hooks
-   Observability, deployment, backups, disaster recovery, and load
    testing

### 1.1 What "complete" means for this PoC

A single repository can demonstrate the main flows and integration
boundaries. It cannot honestly prove internet-scale reliability merely
because it runs locally. We will distinguish three levels:

1.  **Runnable PoC:** core flows work on a developer machine using
    Docker Compose.
2.  **Production-shaped architecture:** explicit contracts, durable
    messaging, idempotency, security, metrics, and failure handling.
3.  **Production deployment:** requires multi-zone infrastructure,
    operational runbooks, capacity planning, security review, load
    tests, disaster-recovery exercises, and real-world abuse controls.

A production system should not be declared ready until the third level
is demonstrated.

------------------------------------------------------------------------

## 2. Functional and non-functional requirements

### 2.1 Functional requirements

  -----------------------------------------------------------------------
  Capability                          Expected behavior
  ----------------------------------- -----------------------------------
  Identity                            Register/login, refresh/revoke
                                      sessions, manage devices

  Direct chat                         Send text messages between two
                                      users

  Group chat                          Create group, add/remove members,
                                      update role and metadata

  Realtime                            Deliver new events over persistent
                                      connections

  Offline delivery                    Persist messages until a device
                                      acknowledges receipt

  Receipts                            Track accepted, delivered, and read
                                      states

  Attachments                         Upload/download media through
                                      object storage

  Multi-device                        Keep a user's linked devices
                                      consistent

  Privacy                             Block users; configure presence and
                                      last-seen visibility

  Notifications                       Notify offline devices without
                                      exposing message plaintext

  Search                              Search permitted metadata or
                                      locally decrypted content; E2EE
                                      limits server-side plaintext search

  Administration                      Abuse reports, throttling, audit
                                      events, and operational controls
  -----------------------------------------------------------------------

### 2.2 Non-functional requirements (NFRs)

The following are **initial design targets**, not claims about
WhatsApp's measured performance. Validate them with load tests and
product requirements.

  -----------------------------------------------------------------------
  NFR                                 Initial target / design choice
  ----------------------------------- -----------------------------------
  Availability                        99.9% for the PoC's critical APIs;
                                      higher targets require multi-zone
                                      operations

  Message durability                  Acknowledged messages survive an
                                      application-process restart;
                                      production durability depends on
                                      replicated storage and broker
                                      configuration

  Send latency                        p95 under 200 ms for server
                                      acceptance in a healthy region;
                                      network and device delivery are
                                      separate

  Realtime delivery                   Best effort while online, with
                                      durable retry for
                                      offline/reconnecting devices

  Ordering                            Per-conversation sequence order; no
                                      global ordering across all chats

  Scale                               Horizontally scale stateless API
                                      and connection gateways

  Consistency                         Strong enough transactional
                                      semantics for message acceptance
                                      and conversation sequence
                                      allocation; eventual delivery and
                                      presence

  Security                            TLS in transit, E2EE for message
                                      content, secure credential storage,
                                      least privilege

  Privacy                             Minimize metadata, configurable
                                      retention, controlled logging,
                                      account/device revocation

  Recovery                            Backups, restore tests, broker
                                      replication, documented RPO/RTO

  Operability                         Metrics, structured logs, traces,
                                      alerts, dashboards, runbooks

  Compatibility                       Versioned APIs and event schemas;
                                      rolling upgrades
  -----------------------------------------------------------------------

**Latency vocabulary matters:**

-   **Accepted:** server has durably accepted the message according to
    the product's durability policy.
-   **Delivered:** at least one intended device has acknowledged
    receiving the message.
-   **Read:** a recipient device has emitted a read event, subject to
    privacy settings.
-   **Displayed:** a UI-level concept and outside this backend-only
    scope.

Do not tell a sender "delivered" merely because a WebSocket write
returned successfully.

------------------------------------------------------------------------

## 3. Scale assumptions and back-of-the-envelope sizing

Sizing should start with explicit assumptions. For a teaching model,
assume:

-   10 million daily active users (DAU)
-   1 million concurrent connections at peak
-   40 messages sent per DAU per day
-   Average logical message payload metadata of 1 KB, excluding media
-   20% of messages sent to groups, with an average of 10 recipients
-   30 days of hot message metadata retention

### 3.1 Message volume

Daily logical messages:

`10,000,000 DAU × 40 messages = 400,000,000 messages/day`

Average accepted messages per second:

`400,000,000 / 86,400 ≈ 4,630 messages/second`

A peak factor of 5 gives roughly 23,000 messages/second. Real systems
need distribution-aware modeling, not just average rates.

### 3.2 Fan-out changes the cost

A direct message generally has one recipient user, but that user may
have multiple devices. A group message may generate many recipient
deliveries.

If 80% of messages are direct and 20% are group messages with 10
recipients on average, a rough recipient-event estimate is:

`0.8 × 1 + 0.2 × 10 = 2.8 recipient deliveries per logical message`

At 23,000 logical messages/second, this implies approximately 64,400
recipient deliveries/second before device fan-out, retries, receipts,
and other events.

This is why **logical message rate and delivery-event rate are different
metrics**.

### 3.3 Storage estimate

At 400 million messages/day and 1 KB of stored metadata per message:

-   Raw metadata: approximately 400 GB/day
-   30 days: approximately 12 TB raw
-   Indexes, replicas, row overhead, and operational headroom increase
    the actual footprint

This is only a rough estimate. Encryption envelopes, event logs,
receipts, indexes, and replication can materially change it. Media is
stored separately in object storage.

### 3.4 Concurrency

A million open WebSocket connections do not imply a million actively
sending users. They do imply memory, file-descriptor, heartbeat,
network, and connection-routing requirements. Test connection density
and reconnect storms separately from message throughput.

------------------------------------------------------------------------

## 4. Recommended technology stack

Use a modular monolith for the first runnable version, with clear
boundaries that can later be extracted into services. Avoid starting
with dozens of microservices: they add operational complexity before the
core message semantics are correct.

  -----------------------------------------------------------------------
  Concern                 Recommended technology  Why
  ----------------------- ----------------------- -----------------------
  Language                Java 25                 Modern JVM, records,
                                                  virtual threads where
                                                  appropriate

  Backend framework       Spring Boot 4.x line    Dependency injection,
                          compatible with Java 25 security, validation,
                                                  observability,
                                                  operational maturity

  HTTP APIs               Spring MVC              Familiar
                                                  request/response APIs

  Realtime                Spring WebSocket or a   Persistent
                          dedicated Netty gateway bidirectional
                                                  connections; isolate
                                                  gateway when connection
                                                  scale requires it

  Authentication          Spring Security, OAuth  Access-token validation
                          2.1/OIDC-compatible     and authorization
                          design                  

  Primary relational data PostgreSQL              Users, devices, groups,
                                                  memberships, settings,
                                                  transactional metadata

  Message/event backbone  Apache Kafka            Partitioned durable
                                                  event stream and
                                                  asynchronous processing

  Fast ephemeral state    Redis                   Presence, rate-limit
                                                  counters,
                                                  connection-routing
                                                  hints, short-lived
                                                  caches

  Media                   S3-compatible object    Durable binary content,
                          storage                 lifecycle policies,
                                                  multipart uploads

  Database migration      Flyway                  Versioned schema
                                                  changes

  API contract            OpenAPI                 Documented and testable
                                                  endpoints

  Serialization           JSON for public APIs;   Evolvable contracts
                          Protobuf/Avro for       
                          internal events where   
                          useful                  

  Observability           Micrometer,             Metrics, traces,
                          OpenTelemetry,          dashboards, diagnosis
                          Prometheus, Grafana,    
                          centralized logs        

  Deployment              Docker; Kubernetes for  Reproducible local and
                          production-like         cluster deployments
                          orchestration           

  Secrets                 Cloud secret manager or Rotation and access
                          Vault                   control

  Testing                 JUnit 5,                Unit, integration,
                          Testcontainers,         contract, and load
                          WireMock, Gatling/k6    testing
  -----------------------------------------------------------------------

**Version note:** Pin exact versions in the repository and verify their
compatibility when creating the project. Framework major versions and
container images change over time. Do not copy floating `latest` tags
into a production deployment.

### Why not use one technology for everything?

-   PostgreSQL is excellent for transactional identity and membership
    data, but a single relational cluster is not automatically the right
    long-term store for every high-volume message and receipt.
-   Kafka is an event backbone, not a user-facing message-history
    database.
-   Redis is a cache/ephemeral-state layer, not the only durable record
    of messages.
-   Object storage is appropriate for large media blobs, not for tiny
    transactional metadata.
-   WebSocket is a connection protocol, not a durable delivery
    guarantee.
-   Push notifications are wake-up signals, not the source of truth for
    chat history.

------------------------------------------------------------------------

## 5. High-level architecture

``` mermaid
flowchart TB
    Client[Mobile / Desktop Clients]
    Edge[DNS / WAF / Load Balancer]
    API[API Gateway / REST API]
    WS[Realtime Gateway]
    Auth[Identity and Device Service]
    Chat[Conversation and Message Service]
    Group[Group Membership Service]
    Media[Media Service]
    Notify[Notification Worker]
    Presence[Presence Service]
    DB[(PostgreSQL)]
    Cache[(Redis)]
    Broker[(Kafka)]
    Obj[(Object Storage)]
    Push[APNs / FCM]
    Metrics[Metrics / Logs / Traces]

    Client --> Edge
    Edge --> API
    Edge --> WS
    API --> Auth
    API --> Chat
    API --> Group
    API --> Media
    Chat --> DB
    Auth --> DB
    Group --> DB
    Chat --> Broker
    Broker --> WS
    Broker --> Notify
    Broker --> DB
    WS --> Cache
    Presence --> Cache
    Media --> Obj
    Notify --> Push
    API --> Metrics
    WS --> Metrics
    Chat --> Metrics
    Broker --> Metrics
```

### Component responsibilities

**API Gateway / REST API** - Validates requests and access tokens -
Applies coarse rate limits and request-size limits - Routes versioned
API calls - Does not contain chat business logic

**Realtime Gateway** - Owns WebSocket connections and heartbeats -
Authenticates the device during connection setup - Maintains
connection-to-device routing - Pushes events to online devices - Does
not treat a successful socket write as durable delivery

**Identity and Device Service** - Manages accounts, sessions, device
registration, and revocation - Stores public identity and key-bundle
metadata - Never stores a user's plaintext password

**Conversation and Message Service** - Validates sender membership and
message limits - Assigns conversation-local sequence numbers - Performs
idempotent message acceptance - Writes durable message state and an
outbox event atomically where supported - Emits events for delivery
processing

**Group Service** - Manages group membership, roles, and membership
epochs - Makes authorization decisions for sending and receiving -
Produces membership-change events

**Media Service** - Authorizes upload/download - Issues short-lived
pre-signed upload/download URLs - Verifies file metadata and scanning
status - Stores blobs in object storage

**Presence Service** - Tracks ephemeral online state and last activity -
Applies privacy settings - Avoids treating presence as durable truth

**Notification Worker** - Sends generic push notifications for offline
devices - Avoids placing plaintext message content in push payloads -
Handles provider failures and retry limits

------------------------------------------------------------------------

## 6. Repository structure

A practical first repository can use a modular Spring Boot application
with packages separated by domain. If traffic or team boundaries justify
it, extract modules later.

``` text
messaging-platform/
├── pom.xml
├── README.md
├── compose.yaml
├── Dockerfile
├── docs/
│   ├── architecture.md
│   ├── api-contracts.md
│   ├── threat-model.md
│   ├── operations-runbook.md
│   └── load-test-plan.md
├── src/main/java/com/example/messaging/
│   ├── MessagingApplication.java
│   ├── identity/
│   │   ├── api/
│   │   ├── application/
│   │   ├── domain/
│   │   └── infrastructure/
│   ├── device/
│   ├── conversation/
│   ├── group/
│   ├── message/
│   ├── delivery/
│   ├── receipt/
│   ├── media/
│   ├── presence/
│   ├── notification/
│   ├── security/
│   └── shared/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
├── src/test/java/
│   ├── unit/
│   ├── integration/
│   └── contract/
└── load-tests/
    ├── websocket-connections.js
    └── send-message.js
```

Keep domain logic independent of Spring annotations where practical.
Controllers translate transport requests; application services
coordinate use cases; domain objects enforce invariants; repositories
and adapters implement infrastructure.

------------------------------------------------------------------------

## 7. Core data model

The schema below is a starting point, not a final schema for every
scale. Partitioning, sharding, and retention should be introduced based
on measured load.

### 7.1 Entities

``` mermaid
erDiagram
    USER ||--o{ DEVICE : owns
    USER ||--o{ CONVERSATION_MEMBER : joins
    CONVERSATION ||--o{ CONVERSATION_MEMBER : contains
    CONVERSATION ||--o{ MESSAGE : contains
    USER ||--o{ MESSAGE : sends
    MESSAGE ||--o{ MESSAGE_DELIVERY : tracks
    DEVICE ||--o{ MESSAGE_DELIVERY : receives
    USER ||--o{ DEVICE_KEY_BUNDLE : publishes
```

Suggested tables:

**users** - `id UUID PRIMARY KEY` - `phone_number_e164` or an
alternative verified identity, uniquely indexed - `display_name` -
`status` - `created_at` - `deleted_at` nullable

**devices** - `id UUID PRIMARY KEY` - `user_id UUID` - `device_type` -
`device_name` - `push_token` (encrypted at rest where appropriate) -
`last_seen_at` - `revoked_at` - `created_at`

**conversations** - `id UUID PRIMARY KEY` - `type` (`DIRECT`, `GROUP`) -
`created_by` - `next_sequence BIGINT` - `created_at` - `last_message_at`

**conversation_members** - `conversation_id` - `user_id` - `role` -
`joined_at` - `left_at` - `membership_epoch` - unique or partial indexes
that enforce the intended active-membership rule

**messages** - `id UUID PRIMARY KEY` - `conversation_id` -
`sequence BIGINT` - `sender_user_id` - `sender_device_id` -
`client_message_id` - `ciphertext` or encrypted payload envelope -
`payload_type` - `created_at` - `expires_at` nullable - unique
`(conversation_id, sequence)` - unique
`(sender_device_id, client_message_id)`

**message_deliveries** - `message_id` - `device_id` - `status` -
`delivered_at` - `read_at` - unique `(message_id, device_id)`

**outbox_events** - `id UUID PRIMARY KEY` - `aggregate_type` -
`aggregate_id` - `event_type` - `payload` - `created_at` -
`published_at` nullable - index on unpublished events

**device_key_bundles** - `device_id` - public identity key - signed
pre-key and signature - one-time pre-key inventory or references -
protocol version - rotation timestamps

**blocks and privacy settings** - blocker, blocked user, creation time -
visibility settings for presence, last seen, profile metadata, and read
receipts

### 7.2 Data-model cautions

1.  Phone numbers are identifiers, not proof of current account
    ownership. Verify them using an appropriate challenge flow.
2.  Never log authentication secrets, private keys, session tokens, push
    tokens, or message plaintext.
3.  A `left_at` field can be important for historical group
    authorization and membership epochs.
4.  Store ciphertext on the server when using E2EE. The server should
    not need message plaintext to route it.
5.  A per-conversation sequence counter can become a hot row for very
    active groups. It is a sensible PoC choice, but may need redesign at
    scale.
6.  Message deletion and retention semantics must be explicit: deletion
    from one device, deletion for everyone, expiration, and legal
    retention are different operations.

------------------------------------------------------------------------

## 8. API design

Use REST for account management, conversation setup, history, and media
authorization. Use WebSocket for realtime events and acknowledgements.

### 8.1 Identity and devices

``` http
POST /v1/auth/register
POST /v1/auth/verify
POST /v1/auth/token
POST /v1/auth/logout
GET  /v1/me
GET  /v1/devices
POST /v1/devices
DELETE /v1/devices/{deviceId}
```

Use short-lived access tokens and rotating refresh tokens or a
comparable session strategy. Refresh-token reuse detection and session
revocation should be part of the design.

### 8.2 Conversations and groups

``` http
POST /v1/conversations/direct
GET  /v1/conversations
GET  /v1/conversations/{conversationId}
GET  /v1/conversations/{conversationId}/messages?beforeSequence=123
POST /v1/groups
POST /v1/groups/{groupId}/members
DELETE /v1/groups/{groupId}/members/{userId}
PATCH /v1/groups/{groupId}
```

Every operation must authorize the caller against current membership and
role. Never trust a client-supplied conversation ID as proof of access.

### 8.3 Message submission

``` http
POST /v1/conversations/{conversationId}/messages
Authorization: Bearer <access-token>
Idempotency-Key: <client-generated-unique-id>
Content-Type: application/json

{
  "clientMessageId": "f2f30e7b-7b8f-4f8e-9e6e-3cb1f0a3a921",
  "senderDeviceId": "device-uuid",
  "payloadType": "TEXT",
  "ciphertext": "base64-encoded-encrypted-envelope",
  "protocolVersion": 1
}
```

Example accepted response:

``` json
{
  "messageId": "server-message-uuid",
  "conversationId": "conversation-uuid",
  "sequence": 124,
  "acceptedAt": "2026-10-22T12:00:00Z",
  "status": "ACCEPTED"
}
```

The server should reject invalid sizes, invalid membership, revoked
devices, malformed envelopes, and replayed identifiers with incompatible
payloads.

### 8.4 WebSocket event envelope

An internal event envelope could look like:

``` json
{
  "eventId": "event-uuid",
  "eventType": "MESSAGE_CREATED",
  "schemaVersion": 1,
  "conversationId": "conversation-uuid",
  "sequence": 124,
  "messageId": "message-uuid",
  "recipientDeviceId": "device-uuid",
  "occurredAt": "2026-10-22T12:00:00Z",
  "payload": {
    "ciphertext": "..."
  }
}
```

Use separate event types for `MESSAGE_CREATED`, `MESSAGE_DELIVERED`,
`MESSAGE_READ`, `GROUP_MEMBER_ADDED`, `GROUP_MEMBER_REMOVED`,
`DEVICE_REVOKED`, and presence changes. Do not make a single opaque
event that clients must interpret through fragile assumptions.

### 8.5 WebSocket protocol

A client connects to a versioned endpoint, for example:

``` text
wss://api.example.com/v1/realtime
```

The handshake authenticates a device. Avoid long-lived bearer tokens in
query strings because URLs often leak into logs. Prefer secure
header-based authentication where the client platform supports it, or a
short-lived one-time connection ticket obtained over HTTPS.

Example client events:

-   `CLIENT_READY`
-   `MESSAGE_ACK`
-   `READ_RECEIPT`
-   `TYPING_START`
-   `TYPING_STOP`
-   `HEARTBEAT`

Example server events:

-   `MESSAGE_CREATED`
-   `MESSAGE_SYNC`
-   `DELIVERY_RECEIPT`
-   `READ_RECEIPT`
-   `PRESENCE_CHANGED`
-   `GROUP_CHANGED`
-   `ERROR`

Define maximum frame size, heartbeat interval, idle timeout, per-device
connection limit, and reconnect backoff. Validate every event as if it
were an untrusted HTTP request.

------------------------------------------------------------------------

## 9. The most important flow: sending a message

``` mermaid
sequenceDiagram
    participant A as Sender Device
    participant G as Realtime/API Gateway
    participant M as Message Service
    participant DB as PostgreSQL
    participant O as Outbox Publisher
    participant K as Kafka
    participant D as Delivery Worker
    participant R as Recipient Gateway
    participant B as Recipient Device

    A->>G: Submit encrypted message + clientMessageId
    G->>M: Authenticated request
    M->>M: Authorize device and conversation membership
    M->>DB: Transaction: allocate sequence, insert message, insert outbox event
    DB-->>M: Commit
    M-->>A: ACCEPTED + messageId + sequence
    O->>DB: Read unpublished outbox rows
    O->>K: Publish MESSAGE_CREATED
    K-->>D: Consume event
    D->>R: Route to recipient device connection
    R->>B: MESSAGE_SYNC
    B-->>R: MESSAGE_ACK
    R->>D: Delivery acknowledgement
    D->>DB: Record device delivery state
```

### 9.1 Why the outbox pattern matters

A naïve implementation does this:

1.  Insert a message in PostgreSQL.
2.  Publish a Kafka event.
3.  Return success.

What if the database commit succeeds but Kafka is unavailable? The
message exists but no delivery event is emitted. What if Kafka succeeds
but the database transaction rolls back? Consumers may process an event
for a message that does not exist.

The **transactional outbox** writes the message and an event row in the
same database transaction. A separate publisher reads pending outbox
rows and publishes them to Kafka. Publishing can happen more than once,
so consumers must be idempotent.

Kafka producer idempotence helps with certain duplicate writes within
Kafka's producer semantics; it does not automatically create one
end-to-end exactly-once transaction spanning PostgreSQL, Kafka,
WebSocket, and a recipient device.

### 9.2 Pseudocode for message acceptance

``` java
@Transactional
public AcceptedMessage acceptMessage(
        AuthenticatedDevice sender,
        UUID conversationId,
        SendMessageCommand command) {

    var existing = messageRepository
        .findBySenderDeviceAndClientMessageId(
            sender.deviceId(), command.clientMessageId());

    if (existing.isPresent()) {
        return AcceptedMessage.from(existing.get());
    }

    membershipPolicy.requireCanSend(sender.userId(), conversationId);
    devicePolicy.requireActive(sender.deviceId());
    payloadPolicy.validate(command);

    long sequence = conversationRepository.allocateNextSequence(conversationId);

    var message = Message.accepted(
        UUID.randomUUID(),
        conversationId,
        sequence,
        sender.userId(),
        sender.deviceId(),
        command.clientMessageId(),
        command.ciphertext(),
        command.payloadType());

    messageRepository.insert(message);
    outboxRepository.insert(MessageCreatedEvent.from(message));

    return AcceptedMessage.from(message);
}
```

This is illustrative pseudocode. Production code must ensure sequence
allocation is atomic, the idempotency key is protected by a database
unique constraint, and a duplicate-key race returns the original
accepted message rather than creating a second one.

### 9.3 Idempotency rules

Clients may retry because they never received the server response. The
server should:

-   Require a stable client-generated message ID for a logical send
    attempt.
-   Enforce a unique constraint on
    `(sender_device_id, client_message_id)`.
-   Return the original accepted message for a compatible retry.
-   Reject reuse of the same idempotency key with a different payload.
-   Retain idempotency records long enough to cover expected retry
    windows.
-   Make downstream consumers idempotent using event IDs or business
    keys.

------------------------------------------------------------------------

## 10. Ordering, retries, and delivery semantics

Distributed systems cannot guarantee that every network event is
observed once, in order, and without failures. Design explicitly for
duplicate events, retries, reconnects, and delayed acknowledgements.

### 10.1 Ordering

A practical guarantee is **per-conversation order**, represented by a
monotonically increasing sequence number. Do not promise global order
across conversations.

Possible implementation: - Lock or atomically update the conversation's
`next_sequence`. - Insert the message with that sequence in the same
transaction. - Enforce unique `(conversation_id, sequence)`. - Partition
Kafka events by `conversationId` when the event ordering contract
requires it.

A hot group can create contention on one sequence allocator or Kafka
partition. At very high scale, consider a different sequencing strategy,
batching, or a more specialized storage design. Those trade-offs should
be measured rather than hidden.

### 10.2 Delivery state machine

``` mermaid
stateDiagram-v2
    [*] --> ACCEPTED
    ACCEPTED --> QUEUED
    QUEUED --> SENT_TO_CONNECTION
    SENT_TO_CONNECTION --> DELIVERED
    QUEUED --> RETRYING
    RETRYING --> QUEUED
    DELIVERED --> READ
    ACCEPTED --> EXPIRED
    QUEUED --> EXPIRED
```

Treat this as a conceptual state model. The exact state transitions
depend on product semantics and whether status is tracked per user or
per device.

-   `ACCEPTED`: durable server acceptance completed.
-   `QUEUED`: pending for a device or recipient.
-   `SENT_TO_CONNECTION`: written to a connection, but not yet
    acknowledged by the device.
-   `DELIVERED`: recipient device acknowledged receipt.
-   `READ`: recipient device acknowledged read, if enabled.
-   `EXPIRED`: retention/expiry policy removed pending delivery.

### 10.3 Offline delivery

When a recipient device reconnects:

1.  Authenticate the device and validate that it is not revoked.
2.  Resume from a server-issued cursor or last acknowledged sequence.
3.  Fetch pending messages in bounded batches.
4.  Send events over the connection.
5.  Let the device acknowledge received messages or a contiguous cursor.
6.  Persist acknowledgement progress.
7.  Resume after reconnect without assuming the previous connection
    completed.

Use backoff with jitter for retries. Cap retry rates and queue growth. A
permanently offline device must not cause unlimited unbounded memory
usage.

### 10.4 Receipts

A group message may have many recipient devices. Define whether receipts
are aggregated per user or reported per device. Keep the source of truth
durable; use asynchronous aggregation for large groups where synchronous
updates would be expensive.

------------------------------------------------------------------------

## 11. Group messaging

Group chat is not just a direct message sent repeatedly. It introduces
membership races, fan-out cost, role-based permissions, and
encryption-key distribution.

### 11.1 Two delivery strategies

**Fan-out on write** - On message acceptance, create recipient delivery
work for each active member/device. - Faster recipient retrieval and
easier offline queues. - High write amplification for large groups.

**Fan-out on read** - Store one logical message and let members fetch it
from the conversation stream. - Lower write amplification. - More work
during reads and more complex cursor management.

**Recommended PoC:** store one canonical message and emit an event; a
delivery worker fans out to currently eligible devices. Keep fan-out
asynchronous. For very large groups, consider hybrid strategies, such as
a threshold-based approach or shared conversation streams with
per-device cursors.

### 11.2 Membership races

Suppose a member is removed while a message is being sent. Decide which
membership snapshot governs authorization.

Use a membership epoch or version: - Every membership change increments
the group's epoch. - Message acceptance validates current membership. -
Events include the relevant epoch. - Delivery workers verify that the
target device remains eligible. - Clients update cryptographic group
state when membership changes.

This reduces stale fan-out, but it does not replace E2EE key rotation. A
removed member who already received old messages may retain those
messages; removal cannot make a device "unlearn" plaintext it previously
decrypted.

### 11.3 Group administration

Implement: - Creator/admin roles - Add/remove members - Leave group -
Rename/change metadata - Admin-only settings - Invite authorization -
Audit events for security-relevant changes - Limits on group size and
mutation rate

------------------------------------------------------------------------

## 12. End-to-end encryption (E2EE)

E2EE is a security architecture, not a field called `encrypted=true`.
The server should route encrypted payloads without possessing the keys
required to decrypt them.

### 12.1 Security goals

-   Message content is encrypted on the sender device.
-   Only intended recipient devices can decrypt it.
-   Server operators and database readers cannot decrypt content using
    server-held keys.
-   Devices can authenticate the cryptographic identity of other
    devices.
-   Forward secrecy and post-compromise security are provided by an
    audited protocol, where applicable.
-   Group membership changes trigger appropriate cryptographic state
    updates.

### 12.2 Do not invent a cryptographic protocol

Use a well-reviewed, established protocol implementation, such as the
Signal Protocol ecosystem where its licensing, supported clients, and
operational constraints fit the product. Obtain a professional security
review before production. Do not create your own key exchange, ratchet,
nonce scheme, or encryption envelope.

A high-level one-to-one setup may use: - Identity key - Signed pre-key -
One-time pre-keys - Authenticated key agreement - A ratcheting session
protocol for subsequent messages

A group system needs a well-defined group key-management protocol. Do
not assume that encrypting the same plaintext with a static shared key
provides the security properties of a modern group-messaging protocol.

### 12.3 Server responsibilities

The server can: - Store public key bundles and signed public metadata -
Return recipient device key bundles - Relay ciphertext envelopes - Track
key-bundle publication and rotation metadata - Enforce device
authorization and rate limits - Store encrypted attachments and
encrypted file keys in message envelopes

The server should not: - Store client private identity keys - Log
plaintext messages - silently substitute recipient public keys - claim
to verify message content it cannot decrypt

### 12.4 Multi-device encryption

A sender may need to encrypt a logical message separately for each
recipient device and for the sender's other linked devices, depending on
the chosen protocol. The server routes a set of ciphertext envelopes.
Device linking and revocation must update the cryptographic device
roster.

### 12.5 Metadata remains sensitive

E2EE protects content, but server-side metadata may still reveal who
communicates with whom, when, approximate message sizes, group
membership, and network information. Minimize collection and retention;
document the privacy model honestly.

### 12.6 Attachments

A common pattern is: 1. Client generates a random file-encryption key.
2. Client encrypts the file locally. 3. Client uploads ciphertext to
object storage. 4. Server returns an opaque media reference. 5. Client
sends the reference and encrypted file key inside the E2EE message. 6.
Recipient downloads ciphertext and decrypts locally.

Do not put decryption keys in ordinary public download URLs. Use
short-lived authorized URLs and appropriate content-type and
malware-scanning policies. For E2EE files, scanning plaintext
server-side conflicts with the server's inability to decrypt; the
product must explicitly choose a privacy-preserving scanning approach or
accept that server-side scanning is limited.

------------------------------------------------------------------------

## 13. Media upload and download

Never proxy every large media byte through the main chat service unless
there is a compelling reason. Use object storage and pre-signed URLs.

``` mermaid
sequenceDiagram
    participant C as Client
    participant M as Media API
    participant O as Object Storage
    participant DB as Metadata Store
    C->>M: Request upload authorization
    M->>M: Authenticate, authorize, enforce quota
    M-->>C: Upload ID + short-lived pre-signed URL
    C->>O: Upload encrypted blob directly
    O-->>C: Upload complete
    C->>M: Confirm upload / finalize metadata
    M->>DB: Store media reference and state
    M-->>C: Media ID
    C->>C: Send media ID and encrypted key in chat message
```

### Media requirements

-   Maximum file sizes by type
-   MIME sniffing rather than trusting client file extensions
-   Upload quotas and per-user rate limits
-   Multipart upload for large files
-   Integrity checks and checksums
-   Malware scanning where compatible with the encryption model
-   Short-lived download authorization
-   Lifecycle rules and orphaned-upload cleanup
-   CDN delivery for eligible encrypted blobs
-   Explicit expiration and deletion semantics

Store media metadata separately from blob bytes. Avoid using predictable
object keys that reveal phone numbers or conversation IDs.

------------------------------------------------------------------------

## 14. Presence and typing indicators

Presence is ephemeral and should not be treated like message history.

### Presence flow

-   Device connects and authenticates.
-   Gateway records a short-lived presence lease in Redis.
-   Heartbeats refresh the lease.
-   Disconnect or lease expiry marks the device offline.
-   Presence service aggregates user-level status across devices.
-   Privacy rules determine whether a particular requester may see the
    status.

Use TTLs so crashed gateways do not leave users permanently online. Use
hysteresis or delayed offline transitions to reduce flicker during short
network interruptions.

Typing indicators should: - Be transient, not durable message rows. -
Expire automatically. - Be rate-limited. - Be sent only to authorized
conversation members. - Avoid exposing activity to blocked or
unauthorized users.

"Last seen" requires careful privacy and retention semantics. Do not
expose precise activity timestamps by default without a clear product
decision.

------------------------------------------------------------------------

## 15. Push notifications

Push notifications are not the message transport of record. APNs and FCM
can delay, collapse, or drop notifications, and devices may be offline.

Recommended behavior: - Send a generic notification such as "You have a
new message." - Avoid plaintext message content in notification payloads
when privacy is a priority. - Include an opaque event or conversation
reference only when necessary and safe. - Use provider-specific retry
limits and backoff. - Handle invalid/revoked push tokens. - Rate-limit
notification bursts for busy groups. - Reconcile messages through the
authenticated sync API when the app reconnects.

The durable message store and synchronization cursor---not the push
provider---are the source of truth.

------------------------------------------------------------------------

## 16. Storage and partitioning strategy

### 16.1 PostgreSQL first

For a PoC, PostgreSQL can hold: - Users and devices - Conversation
metadata - Memberships - Message records for moderate load - Delivery
cursors and outbox records

Use indexes intentionally. Example indexes: -
`messages(conversation_id, sequence DESC)` -
`messages(sender_device_id, client_message_id)` unique -
`conversation_members(user_id, conversation_id)` -
`outbox_events(published_at, created_at)` for pending rows

Use keyset pagination (`beforeSequence` or a cursor), not large offset
pagination.

### 16.2 When to partition

Potential partition keys include: - Conversation ID - Time window -
Tenant/region - A stable hash of conversation ID

The correct choice depends on access patterns, retention, cross-region
needs, and hot conversations. Time partitioning helps retention deletion
but may not evenly distribute a hot conversation. Hash partitioning
spreads data but complicates global operational tasks.

### 16.3 When to add a distributed message store

Consider a wide-column or distributed key-value store when measured
write throughput, retention volume, or regional distribution outgrows
the relational design. Do not add Cassandra/ScyllaDB simply because the
system resembles a famous app. Define the access patterns first and test
consistency, repair, compaction, and operational complexity.

### 16.4 Redis usage

Appropriate: - Online presence leases - Rate-limit counters - Gateway
routing hints - Short-lived deduplication caches - Hot metadata caches

Not appropriate as the only source of truth for accepted messages or
delivery receipts that must survive restart.

------------------------------------------------------------------------

## 17. Kafka topics and event processing

Example topic design:

  -------------------------------------------------------------------------
  Topic                     Partition key           Example event
  ------------------------- ----------------------- -----------------------
  `message-created`         conversation ID         Canonical message
                                                    accepted

  `message-delivery`        recipient device ID or  Delivery attempt
                            conversation ID,        
                            depending on ordering   

  `message-receipts`        message ID or           Delivered/read receipt
                            conversation ID         

  `group-events`            group/conversation ID   Membership change

  `device-events`           user/device ID          Device linked or
                                                    revoked

  `notification-requests`   recipient user ID       Generic push request

  `dead-letter` topics      original key            Permanently failed
                                                    event
  -------------------------------------------------------------------------

Topic names and partition keys must reflect the desired ordering
contract. A single partition key cannot simultaneously optimize every
workload.

### Consumer requirements

-   Commit offsets only after the relevant work is durably completed, or
    use a carefully designed transactional/outbox approach.
-   Make handlers idempotent.
-   Use bounded retries and backoff.
-   Route poison messages to dead-letter handling with alerts.
-   Preserve event schema compatibility.
-   Monitor consumer lag, partition skew, and rebalance duration.
-   Avoid blocking event-loop threads with slow database or network
    calls.

Do not blindly retry permanent errors such as invalid schema or revoked
membership forever.

------------------------------------------------------------------------

## 18. Security and abuse prevention

### 18.1 Transport and authentication

-   TLS for every external connection; secure internal transport
    according to threat model.
-   Short-lived access tokens and revocable sessions.
-   Secure refresh-token rotation and reuse detection.
-   Device binding and device revocation.
-   MFA or stronger recovery safeguards for sensitive account
    operations.
-   Protect against brute force, OTP abuse, credential stuffing, and
    account enumeration.
-   Never log secrets or authentication headers.

### 18.2 Authorization

Check authorization at the server for every message send, history read,
media access, group mutation, and receipt. Do not rely on a
client-supplied conversation ID, member list, or role.

### 18.3 Rate limiting

Apply layered limits: - Per IP/network - Per account - Per device - Per
conversation - Per OTP destination - Per media upload - Per group
mutation - Per WebSocket event type

Use Redis or a gateway-level limiter for fast shared counters, but
ensure a Redis outage does not accidentally disable all abuse controls.
Define safe degraded behavior.

### 18.4 Data protection

-   Encrypt disks, backups, and secrets at rest.
-   Use a managed KMS for server-side encryption keys.
-   Keep E2EE private keys on clients.
-   Apply least privilege to service identities.
-   Separate production and non-production data.
-   Redact logs and traces.
-   Define data deletion, account deletion, retention, and backup-expiry
    policies.
-   Conduct threat modeling and dependency vulnerability scanning.

### 18.5 Abuse and trust/safety

A messaging service needs more than cryptography: - User blocking - Spam
and unsolicited-message controls - Report workflows with a clear
user-consent model - Device/account rate limits - Suspicious signup
detection - Group invitation limits - Fraud and abuse telemetry with
minimization - Appeals and false-positive review procedures

E2EE constrains what the server can inspect. Safety mechanisms should
not quietly undermine the stated privacy model.

------------------------------------------------------------------------

## 19. Reliability patterns

### 19.1 Timeouts, retries, and circuit breakers

Every remote call needs a timeout. Retry only transient failures, use
exponential backoff with jitter, and avoid retrying non-idempotent
operations without idempotency keys. Circuit breakers can prevent a
failing downstream dependency from exhausting the service.

### 19.2 Backpressure

If recipient delivery falls behind: - Bound per-device and
per-conversation queues. - Persist pending work rather than accumulating
it in process memory. - Slow or reject abusive producers. - Separate
priority traffic carefully. - Track queue age, not only queue depth. -
Shed optional work before core message acceptance where safe.

### 19.3 Graceful degradation

  -----------------------------------------------------------------------
  Failure                             Desired behavior
  ----------------------------------- -----------------------------------
  Presence Redis unavailable          Messaging continues; presence
                                      becomes stale/unknown

  Push provider unavailable           Durable message remains;
                                      notification is retried within
                                      limits

  Recipient offline                   Message stays pending for sync

  WebSocket gateway crashes           Client reconnects with backoff and
                                      resumes cursor

  Kafka consumer lag                  Acceptance continues only within
                                      configured durable-outbox capacity;
                                      alert and apply backpressure

  Object storage unavailable          Text messaging continues; media
                                      upload/download may be unavailable

  Read-receipt subsystem degraded     Message delivery remains
                                      independent where possible

  Database unavailable                Stop acknowledging new messages
                                      that cannot be durably committed
  -----------------------------------------------------------------------

Do not return success for a message that has not met the system's
declared durability condition.

### 19.4 Disaster recovery

Define: - **RPO:** maximum acceptable data loss measured in time. -
**RTO:** maximum acceptable recovery duration.

Use replicated databases and brokers, backups with encryption, tested
restore procedures, and documented regional-failover steps. A backup
that has never been restored is not a proven recovery plan.

------------------------------------------------------------------------

## 20. Observability

### Key metrics

**API** - Request rate, p50/p95/p99 latency, error rate - Authentication
failures and rate-limit rejections - Message acceptance latency

**Realtime** - Active connections - Connect/disconnect rate - Reconnect
storms - Heartbeat timeout rate - Events per connection - Per-gateway
memory and file descriptors

**Messaging** - Accepted messages per second - Outbox oldest-row age -
Kafka consumer lag - Pending deliveries and oldest pending age -
Delivery acknowledgement latency - Duplicate/idempotent retry count -
Receipt processing latency - Hot conversation/partition indicators

**Infrastructure** - Database connection pool saturation - Query latency
and lock contention - Kafka under-replicated partitions - Redis latency
and evictions - Object-storage error rate - CPU, memory, GC pauses,
network throughput

### Logging and tracing

Use structured logs with correlation IDs and event IDs. Do not put
message bodies, ciphertext keys, tokens, phone numbers, or other
sensitive personal data into logs by default. Ciphertext can still be
sensitive metadata and should not be logged indiscriminately.

Trace the path from API acceptance through outbox publication and
delivery worker. Sampling should be designed so high-volume traffic does
not create an unbounded telemetry bill.

### Example SLOs

-   99.9% successful durable acceptance for valid sends during a rolling
    window
-   p95 server acceptance latency below 200 ms under the target load
-   p95 online-device delivery acknowledgement below 1 second in a
    healthy region, measured separately from acceptance
-   99% of outbox events published within 5 seconds under normal load
-   Recovery time and recovery point meeting the agreed DR objectives

These are example objectives, not guarantees. Define exclusions and
measurement windows before using them for operational commitments.

------------------------------------------------------------------------

## 21. Java 25 and Spring Boot implementation guidance

### 21.1 Keep the domain model explicit

Example immutable command:

``` java
public record SendMessageCommand(
        UUID clientMessageId,
        UUID senderDeviceId,
        String payloadType,
        String ciphertext,
        int protocolVersion) {
}
```

Validate input at the API boundary: - Maximum ciphertext length -
Allowed payload type - Supported protocol version - Non-null
identifiers - Authenticated device matches the request context - Valid
conversation membership

Use Jakarta Bean Validation for structural checks and domain policies
for business invariants.

### 21.2 Transactions

Use a database transaction for: - Idempotency lookup/unique-key
protection - Sequence allocation - Message insert - Outbox insert

Avoid making remote network calls while holding database locks. Do not
keep a transaction open while waiting for Kafka, WebSocket, or
push-provider responses.

### 21.3 Virtual threads

Java virtual threads can simplify blocking I/O concurrency in
appropriate Spring MVC workloads. They do not make CPU-heavy encryption,
JSON processing, or image transformation free. They also do not remove
database connection-pool limits, remote service capacity limits, or
backpressure requirements.

Use: - Virtual threads where blocking I/O and the framework support make
them beneficial. - Bounded executors for CPU-intensive tasks. -
Dedicated connection limits for databases and remote clients. - Load
tests to verify throughput and tail latency.

Do not create an unbounded queue just because virtual threads are cheap.

### 21.4 WebSocket connection architecture

For a small PoC, Spring WebSocket is sufficient. For very high
concurrent connection counts, evaluate a dedicated Netty-based gateway
or a separately scaled realtime service.

The gateway must not assume that any gateway can directly access any
socket. Maintain a routing mechanism: - Device-to-gateway mapping with a
lease/TTL - A broker or internal gateway bus for cross-instance
routing - Reconnect-safe registration and deregistration - Protection
against stale mappings - Device session limits and heartbeat monitoring

### 21.5 Configuration

Externalize: - Database and broker endpoints - Credentials and secret
references - TLS settings - Message size limits - WebSocket heartbeat
intervals - Retention periods - Rate limits - Topic names - Feature
flags

Do not commit real secrets to Git. Provide `.env.example` with
placeholders only.

------------------------------------------------------------------------

## 22. Example application configuration

Illustrative configuration; exact property names depend on the selected
Spring Boot and library versions.

``` yaml
spring:
  application:
    name: messaging-platform
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/messaging}
    username: ${DB_USERNAME:messaging}
    password: ${DB_PASSWORD:change-me}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true

app:
  messaging:
    max-ciphertext-bytes: 65536
    idempotency-retention: PT72H
    delivery-batch-size: 200
  websocket:
    heartbeat-interval: PT25S
    idle-timeout: PT90S
  security:
    access-token-issuer: ${TOKEN_ISSUER:https://auth.example.com}
```

The sample password is a local placeholder, not a production secret.
Production configuration should be injected through a secret manager and
deployment configuration.

------------------------------------------------------------------------

## 23. Local development environment

A local `compose.yaml` should run the dependencies needed for the PoC: -
PostgreSQL - Kafka in a supported local configuration - Redis -
S3-compatible object storage such as MinIO - Optional Prometheus and
Grafana

The application should start with documented commands such as:

``` bash
docker compose up -d
./mvnw clean verify
./mvnw spring-boot:run
```

These commands are a target developer workflow, not a claim that a
repository has been generated or tested. A complete implementation
should include health checks, readiness conditions, persistent
development volumes where appropriate, and a cleanup command.

Avoid using development credentials or single-node broker settings as
evidence of production availability.

------------------------------------------------------------------------

## 24. Testing strategy

### 24.1 Unit tests

Test: - Membership and role authorization - Idempotency rules - Sequence
allocation policy - Message-size and payload validation - Group
membership epoch rules - Receipt state transitions - Privacy and
blocking rules

### 24.2 Integration tests

Use Testcontainers for PostgreSQL, Kafka, and Redis where appropriate.
Test: - Transactional message + outbox insertion - Duplicate send
retries - Publisher crash after Kafka publish but before marking outbox
complete - Consumer duplicate processing - Device reconnect and cursor
resumption - Database rollback - Revoked-device rejection - Media
authorization expiry

### 24.3 Contract tests

Validate: - REST request and response schemas - WebSocket event schema
versions - Event compatibility across rolling deployments - Consumer
behavior when unknown optional fields appear - API backward
compatibility

### 24.4 Load tests

Test separate workloads rather than one aggregate number:

1.  Many idle WebSocket connections with heartbeats.
2.  Steady one-to-one messaging.
3.  Burst traffic after a regional reconnect.
4.  Large groups with fan-out.
5.  Offline recipients and growing pending queues.
6.  Read-history pagination.
7.  Media upload and download.
8.  Database failover and broker lag.
9.  Duplicate retries and delayed acknowledgements.
10. A hot conversation that stresses sequence allocation.

Measure p50/p95/p99, error rate, queue age, CPU, memory, GC pauses,
database pool utilization, Kafka lag, and network throughput. A load
test is useful only if the environment, data distribution, client
behavior, and acceptance criteria are documented.

------------------------------------------------------------------------

## 25. Deployment architecture

### Development

-   One application instance
-   Docker Compose dependencies
-   Local test data
-   Simplified secrets and observability

### Staging

-   Multiple application instances
-   Managed or replicated database and broker configuration
-   Production-like authentication and TLS
-   Load tests and failure injection
-   Data anonymization

### Production

-   Multi-zone application deployment
-   Separate scaling for API, realtime gateway, delivery workers, and
    notification workers
-   Managed or properly operated PostgreSQL/Kafka/Redis/object storage
-   Autoscaling based on appropriate signals
-   Network policies and least-privilege identities
-   Secret manager and key-management service
-   Rolling/blue-green deployment strategy
-   Schema migration compatibility
-   Backup and restore automation
-   On-call dashboards and runbooks
-   Regional recovery plan

### Kubernetes considerations

Use readiness probes to stop routing traffic to unready instances,
liveness probes to recover genuinely stuck processes, and startup probes
for slow startup. Do not let liveness probes trigger restart loops
because a downstream dependency is temporarily unavailable.

For WebSocket gateways, plan connection draining during rollout. A pod
termination should stop accepting new connections, notify clients when
appropriate, allow a bounded drain period, and then close remaining
connections so clients reconnect with backoff.

------------------------------------------------------------------------

## 26. Common design mistakes

1.  **Calling WebSocket delivery "exactly once."** Network delivery is
    retryable and can duplicate. Use acknowledgements, cursors, and
    idempotency.
2.  **Publishing to Kafka after committing the database without an
    outbox.** This creates a dual-write failure window.
3.  **Storing presence as durable message data.** Presence is ephemeral.
4.  **Keeping every pending message in application memory.** Process
    crashes and queue growth will lose or exhaust state.
5.  **Using Redis as the only message store.** Cache eviction or restart
    can destroy durable history.
6.  **Treating push notifications as delivery proof.** Push delivery is
    not a reliable message acknowledgement.
7.  **Encrypting with a home-grown scheme.** Use an established,
    reviewed protocol.
8.  **Ignoring multi-device behavior.** Each device has independent
    connection, key, and acknowledgement state.
9.  **Assuming group membership never changes during delivery.** Use
    authorization checks and membership versioning.
10. **Building microservices too early.** Start with clear modules and
    extract only when scaling, ownership, or isolation requires it.
11. **Optimizing average latency only.** Tail latency and reconnect
    storms matter.
12. **Claiming production readiness from a local demo.** Production
    readiness is established through testing, security review,
    operational ownership, and recovery drills.

------------------------------------------------------------------------

## 27. Suggested implementation roadmap

### Phase 1 --- Foundation

-   Create Spring Boot application and package boundaries
-   Add PostgreSQL migrations
-   Implement identity, device registration, and authorization
-   Add structured logs, health endpoints, and integration tests

**Exit criteria:** authenticated users and devices can be created and
revoked safely.

### Phase 2 --- Durable direct messages

-   Create direct conversations
-   Implement idempotent message acceptance
-   Add conversation sequence allocation
-   Add message history pagination
-   Implement transactional outbox

**Exit criteria:** retrying a send does not create a second logical
message; accepted messages survive application restart.

### Phase 3 --- Realtime delivery

-   Add WebSocket authentication
-   Add gateway registration and device routing
-   Publish outbox events to Kafka
-   Implement recipient delivery worker and device acknowledgement
-   Add reconnect cursor synchronization

**Exit criteria:** online delivery works; offline messages are
synchronized after reconnect; duplicate events are harmless.

### Phase 4 --- Groups and receipts

-   Add group roles and membership epochs
-   Add group fan-out
-   Add delivered/read receipts
-   Test membership changes during delivery

**Exit criteria:** authorization and receipt behavior remain correct
under retries and membership races.

### Phase 5 --- Media and notifications

-   Add pre-signed media uploads
-   Add encrypted attachment metadata
-   Add generic push notifications
-   Add quota, size, retention, and cleanup policies

**Exit criteria:** large media bytes bypass the chat service and access
remains authorized.

### Phase 6 --- E2EE and multi-device

-   Integrate an established protocol implementation on clients
-   Add public key-bundle lifecycle
-   Add device linking/revocation behavior
-   Define encrypted group messaging and key rotation
-   Conduct a threat model and external review

**Exit criteria:** cryptographic behavior is tested with protocol test
vectors and reviewed by specialists; the server does not require message
plaintext.

### Phase 7 --- Production hardening

-   Add rate limits and abuse controls
-   Add load tests and fault injection
-   Add dashboards, alerts, runbooks, backup/restore tests
-   Deploy to a multi-zone staging environment
-   Validate SLOs and recovery objectives

**Exit criteria:** documented evidence supports readiness; unresolved
risks are explicit.

------------------------------------------------------------------------

## 28. Interview-ready trade-off summary

  -----------------------------------------------------------------------
  Question                            Strong answer
  ----------------------------------- -----------------------------------
  Why WebSocket?                      Persistent bidirectional connection
                                      reduces polling overhead and
                                      enables low-latency
                                      server-to-client events; it still
                                      needs durable delivery semantics.

  Why Kafka?                          Durable, partitioned event
                                      processing decouples acceptance
                                      from delivery, notifications, and
                                      analytics.

  Why an outbox?                      It avoids losing an event between
                                      database commit and broker publish;
                                      consumers still need idempotency.

  Why not exactly-once delivery?      A distributed path spans database,
                                      broker, network, and device. Prefer
                                      at-least-once processing with
                                      idempotent effects and explicit
                                      acknowledgements.

  Why PostgreSQL first?               Strong transactions and a simple
                                      operational model make it a good
                                      PoC foundation; measured scale may
                                      justify a different message store
                                      later.

  How to scale WebSockets?            Scale gateway instances
                                      horizontally, route by
                                      device-to-gateway leases,
                                      externalize shared state, and
                                      handle reconnects and draining.

  How to handle offline users?        Persist messages and cursors, retry
                                      bounded delivery, and resume
                                      through sync after reconnect.

  How to scale groups?                Use asynchronous fan-out,
                                      membership epochs, backpressure,
                                      and potentially hybrid
                                      fan-out-on-write/read for large
                                      groups.

  How to provide E2EE?                Use an audited protocol
                                      implementation, client-side keys,
                                      authenticated device identity, key
                                      rotation, and encrypted media;
                                      never invent cryptography.

  How to preserve order?              Assign per-conversation sequence
                                      numbers and partition relevant
                                      events consistently; recognize
                                      hot-conversation contention.

  What if Kafka is down?              The outbox retains pending events
                                      while bounded capacity remains;
                                      alert and apply backpressure rather
                                      than silently losing accepted
                                      messages.

  What does delivered mean?           A recipient device acknowledged
                                      receipt according to the protocol,
                                      not merely that a server wrote
                                      bytes to a socket.
  -----------------------------------------------------------------------

------------------------------------------------------------------------

## 29. Production readiness checklist

### Correctness

-   [ ] Idempotency enforced by database constraints
-   [ ] Message and outbox commit atomically
-   [ ] Consumer side effects are idempotent
-   [ ] Per-conversation ordering contract documented
-   [ ] Offline sync cursor and gap recovery tested
-   [ ] Multi-device delivery semantics documented
-   [ ] Group membership races tested

### Security and privacy

-   [ ] TLS enforced
-   [ ] Authentication, refresh, and revocation tested
-   [ ] Authorization enforced on every resource
-   [ ] E2EE protocol reviewed
-   [ ] No plaintext or secrets in logs
-   [ ] Media URLs short-lived and scoped
-   [ ] Rate limits and abuse workflows enabled
-   [ ] Retention/deletion policies documented

### Reliability

-   [ ] Database and broker replication configured
-   [ ] Backups restored successfully in a test
-   [ ] RPO/RTO agreed and tested
-   [ ] Queues bounded and backpressure implemented
-   [ ] Retries use jitter and have limits
-   [ ] Dependency failures tested
-   [ ] Reconnect storms tested

### Operations

-   [ ] SLOs and dashboards defined
-   [ ] Alerts have actionable runbooks
-   [ ] Schema and event compatibility tested
-   [ ] Rolling deployment and connection draining tested
-   [ ] Capacity plan based on load-test evidence
-   [ ] Security scanning and dependency updates automated

------------------------------------------------------------------------

## 30. Final architecture recommendation

For a realistic learning project, start with:

-   **Java 25 + Spring Boot**
-   **Spring MVC** for REST APIs
-   **Spring WebSocket** for the initial realtime gateway
-   **PostgreSQL** for identity, conversations, messages, and
    transactional outbox
-   **Kafka** for durable asynchronous event processing
-   **Redis** for presence and ephemeral routing/rate-limit state
-   **S3-compatible object storage** for encrypted media
-   **Micrometer + OpenTelemetry + Prometheus/Grafana** for
    observability
-   **Docker Compose** for local development, then Kubernetes or managed
    equivalents for staging and production
-   **An established E2EE protocol implementation** rather than custom
    cryptography

Keep the first version modular. Implement durable acceptance and offline
synchronization before optimizing connection counts or adding exotic
storage. The central design principle is:

> **Persist the message before acknowledging acceptance, publish
> delivery work reliably, expect retries and duplicates, and let the
> recipient device---not the socket write---confirm delivery.**

That principle, combined with explicit security boundaries,
per-conversation ordering, bounded backpressure, and measurable
operational objectives, provides a strong foundation for a WhatsApp-like
backend.

## Further reading

Use the official documentation for implementation details and version
compatibility:

-   Java: https://docs.oracle.com/en/java/
-   Spring Boot: https://docs.spring.io/spring-boot/
-   Spring Security: https://docs.spring.io/spring-security/reference/
-   Spring WebSocket:
    https://docs.spring.io/spring-framework/reference/web/websocket.html
-   PostgreSQL: https://www.postgresql.org/docs/
-   Apache Kafka: https://kafka.apache.org/documentation/
-   Redis: https://redis.io/docs/
-   OpenTelemetry: https://opentelemetry.io/docs/
-   Kubernetes: https://kubernetes.io/docs/
-   Signal Protocol: https://signal.org/docs/

------------------------------------------------------------------------

*End of blog. This document is a system design and implementation
blueprint. It is not a claim that a runnable repository, audited
encryption implementation, or production deployment has already been
built or tested.*
