# UPI Offline Mesh --- Deferred Offline Payment Demo

A Spring Boot application that demonstrates how an offline payment
instruction can be relayed across a **simulated Bluetooth-style mesh
network** and submitted to a backend when a device regains internet
connectivity.

Imagine sending ₹500 to a friend in a basement with no network coverage.
The sender's payment instruction is encrypted and passed between nearby
devices. The packet is eventually carried by a device with internet
access and uploaded to the backend, where it is validated, deduplicated,
and settled.

This project implements the **backend and a software-based mesh
simulator** so the complete flow can be demonstrated locally on a single
machine without Bluetooth hardware.

> **Scope:** This is an educational prototype for mesh-routed deferred
> settlement. It is not a real UPI integration, does not connect to NPCI
> or a bank, and does not implement real Bluetooth communication.

## Key Features

-   **Simulated offline mesh:** Virtual devices exchange payment packets
    through gossip rounds, with a time-to-live (TTL) value limiting
    packet propagation.
-   **Hybrid encryption:** RSA-OAEP protects a per-packet AES key, while
    AES-256-GCM encrypts and authenticates the payment payload.
-   **Ciphertext-based idempotency:** SHA-256 hashes identify duplicate
    packet deliveries before decryption and settlement.
-   **Replay freshness validation:** Encrypted payment instructions
    carry a timestamp and nonce; stale packets are rejected.
-   **Transactional settlement:** Sender debit, receiver credit, and
    transaction-ledger insertion run in a database transaction.
-   **Concurrency protection:** Atomic in-memory idempotency claims, a
    unique database constraint, and optimistic locking protect the demo
    against concurrent duplicate submissions.
-   **Interactive dashboard:** A browser-based dashboard drives the
    payment, mesh gossip, bridge upload, and settlement flow.
-   **REST API:** Endpoints expose account balances, transaction
    history, mesh state, and bridge ingestion.
-   **Automated tests:** Tests cover encryption/decryption, tampered
    ciphertext rejection, and concurrent duplicate ingestion.

## Technology Stack

  Area           Technologies
  -------------- -------------------------------------------------
  Language       Java 17
  Backend        Spring Boot 3.3, Spring Web
  Persistence    Spring Data JPA, H2 in-memory database
  Cryptography   RSA-OAEP, AES-256-GCM, SHA-256
  Concurrency    Java concurrency utilities, `ConcurrentHashMap`
  Build          Maven Wrapper
  UI             HTML dashboard served by Spring Boot
  Testing        JUnit

## Architecture Overview

The application models four logical parts:

1.  **Sender simulation** creates a payment instruction containing
    sender, receiver, amount, nonce, and creation time.
2.  **Mesh simulator** moves the encrypted packet between virtual
    devices. Intermediate devices carry the packet but do not need to
    decrypt its contents.
3.  **Bridge device** represents a device that has regained internet
    connectivity and uploads the packet to the backend.
4.  **Spring Boot backend** checks duplicate delivery, decrypts and
    validates the instruction, and settles the payment.

``` text
 Sender simulation
       |
       | Create payment instruction
       v
 Hybrid encryption
 RSA-OAEP + AES-256-GCM
       |
       v
 Encrypted mesh packet
       |
       | Simulated gossip / device-to-device hops
       v
 Virtual devices
       |
       | Bridge device regains connectivity
       v
 POST /api/bridge/ingest
       |
       v
 SHA-256 ciphertext hash
       |
       v
 Idempotency claim
       |
       v
 Decrypt and validate freshness
       |
       v
 Transactional settlement
       |
       +--> Debit sender
       +--> Credit receiver
       +--> Write transaction ledger
```

## How the Payment Flow Works

### 1. Create and encrypt a payment

The demo simulates a sender phone and creates a `PaymentInstruction`
containing the payment details, a unique nonce, and a timestamp.

The payload is encrypted using a hybrid encryption approach:

1.  Generate a fresh AES-256 key for the payment packet.
2.  Encrypt the payment payload using AES-256-GCM.
3.  Encrypt the AES key using the server's RSA public key with RSA-OAEP.
4.  Package the encrypted key, IV, and encrypted payload into the packet
    ciphertext.

The resulting `MeshPacket` includes metadata such as packet ID, TTL,
creation time, and ciphertext.

### 2. Relay the packet through the simulated mesh

The mesh simulator models virtual devices exchanging packets during
gossip rounds. Each round allows devices to pass packets to other
simulated devices, and the packet TTL is decremented as it travels.

The simulator represents the behavior of a Bluetooth-style mesh at the
application level. It does **not** establish real Bluetooth or Wi-Fi
Direct connections.

### 3. Upload through a bridge device

A virtual device configured with internet connectivity acts as the
bridge. When the dashboard triggers a bridge flush, that device submits
its packets to:

``` http
POST /api/bridge/ingest
```

The backend then processes each packet.

### 4. Validate and settle

The ingestion pipeline performs these operations:

1.  Computes a SHA-256 hash of the ciphertext.
2.  Attempts to claim the hash in the idempotency cache.
3.  Rejects the packet if the hash has already been claimed.
4.  Decrypts the payload using the server's RSA private key and AES-GCM.
5.  Checks that the payment timestamp is within the accepted freshness
    window.
6.  Settles the payment inside a database transaction.

Settlement debits the sender, credits the receiver, and inserts a
transaction-ledger record. The `Account` entity uses optimistic locking
through JPA's `@Version` field.

## Design Decisions

### 1. Protecting payment data while it is relayed

Intermediate devices should not need access to payment details. The
backend's public key is used to protect the AES key, and AES-GCM
encrypts and authenticates the payment payload.

-   RSA-OAEP encrypts the AES key.
-   AES-256-GCM encrypts the payment instruction and detects ciphertext
    tampering.
-   The server's private key is required to recover the AES key and
    decrypt the payload.

If the ciphertext or authenticated data is modified, GCM authentication
fails during decryption.

### 2. Handling duplicate packet delivery

A packet may reach the backend more than once if it is copied across the
simulated mesh or uploaded by multiple bridge devices.

The ingestion service calculates:

``` text
SHA-256(ciphertext)
```

It then attempts an atomic claim using `ConcurrentHashMap.putIfAbsent`.
Only the first thread to claim a hash proceeds through the ingestion
pipeline; subsequent attempts are treated as duplicates.

The transaction table also has a unique constraint on the packet hash as
a database-level safeguard.

**Important implementation boundary:** The `ConcurrentHashMap` is local
to one JVM. It demonstrates atomic duplicate detection within this
application instance, but it is not a distributed idempotency store. A
multi-instance production deployment would need shared coordination, for
example Redis with an appropriate atomic operation, alongside a database
uniqueness constraint and carefully designed transaction handling.

### 3. Rejecting stale and replayed packets

The encrypted payment instruction contains a timestamp and a unique
nonce.

-   The backend rejects instructions outside the configured freshness
    window.
-   Re-delivery of the same encrypted packet produces the same
    ciphertext hash and is rejected by the idempotency mechanism.
-   Separate payment instructions can have distinct nonces and
    ciphertexts, even when the sender, receiver, and amount are the
    same.

The timestamp and nonce are fields inside the encrypted payload; this
prototype does not implement a separate sender digital-signature scheme.

### 4. Keeping settlement atomic

The settlement operation performs the sender debit, receiver credit, and
ledger insertion in a single `@Transactional` operation. Optimistic
locking on account records helps detect conflicting updates.

The in-memory idempotency cache, database unique constraint, and
transactional settlement provide layered safeguards in this
single-instance demo. They should not be interpreted as a complete
distributed exactly-once guarantee for a production payment platform.

## Getting Started

### Prerequisites

-   JDK 17 or newer
-   Git

The project includes the Maven Wrapper, so a separate Maven installation
is not required.

### Clone the repository

``` bash
git clone <YOUR_REPOSITORY_URL>
cd <YOUR_PROJECT_DIRECTORY>
```

Replace the placeholders with your repository URL and local directory
name.

### Run on Windows

``` cmd
mvnw.cmd spring-boot:run
```

### Run on macOS or Linux

``` bash
./mvnw spring-boot:run
```

On the first run, the Maven Wrapper downloads Maven and the required
dependencies.

### Run with Docker Compose

Docker Compose starts MySQL and the application with persistent database and
server-key volumes.

~~~ bash
cp .env.example .env
# Edit .env and replace both placeholder secrets.
docker compose up --build
~~~

On Windows PowerShell, create the environment file with:

~~~ powershell
Copy-Item .env.example .env
~~~

Then edit .env, replace both placeholder values, and run:

~~~ powershell
docker compose up --build
~~~

### Open the dashboard

After the application starts, open:

``` text
http://localhost:8080
```

Use the dashboard to create a payment, run mesh gossip rounds, upload
packets through the bridge device, and inspect account balances and the
transaction ledger.

### Stop the application

Press `Ctrl+C` in the terminal running the application.

## Running the Tests

Run the complete test suite:

**Windows**

``` cmd
mvnw.cmd test
```

**macOS / Linux**

``` bash
./mvnw test
```

The concurrency test can also be run independently:

``` cmd
mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByThreeBridgesSettlesExactlyOnce
```

The test exercises simultaneous ingestion of the same packet by three
threads and checks that only one settlement is recorded.

### Test coverage

  ---------------------------------------------------------------------------------------------
  Test                                                      Purpose
  --------------------------------------------------------- -----------------------------------
  `encryptDecryptRoundTrip`                                 Verifies that a payload encrypted
                                                            by the hybrid encryption service
                                                            can be decrypted successfully.

  `tamperedCiphertextIsRejected`                            Modifies ciphertext and verifies
                                                            that ingestion rejects it rather
                                                            than settling it.

  `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce`   Runs concurrent ingestion attempts
                                                            for one packet and checks that only
                                                            one settlement succeeds.
  ---------------------------------------------------------------------------------------------

## API Reference

  -----------------------------------------------------------------------
  Method                  Endpoint                Description
  ----------------------- ----------------------- -----------------------
  GET                     `/`                     Serves the interactive
                                                  dashboard.

  GET                     `/api/server-key`       Returns the server's
                                                  RSA public key in
                                                  Base64 format.

  GET                     `/api/accounts`         Returns account
                                                  balances.

  GET                     `/api/transactions`     Returns the latest 20
                                                  transactions.

  GET                     `/api/mesh/state`       Returns the current
                                                  state of virtual mesh
                                                  devices.

  POST                    `/api/demo/send`        Simulates a sender,
                                                  encrypts a payment, and
                                                  injects the packet into
                                                  the mesh.

  POST                    `/api/mesh/gossip`      Runs one round of
                                                  packet gossip between
                                                  virtual devices.

  POST                    `/api/mesh/flush`       Uploads packets from
                                                  bridge devices to the
                                                  backend.

  POST                    `/api/mesh/reset`       Resets the mesh and its
                                                  idempotency cache.

  POST                    `/api/bridge/ingest`    Accepts an encrypted
                                                  packet from a bridge
                                                  device.

  GET                     `/h2-console`           Opens the H2 database
                                                  console.
  -----------------------------------------------------------------------

### Bridge ingestion request

Example request:

``` http
POST /api/bridge/ingest
Content-Type: application/json
X-Bridge-Node-Id: phone-bridge-42
X-Hop-Count: 3
X-Bridge-Api-Key: <UPI_MESH_BRIDGE_API_KEY>
```

``` json
{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "base64-encoded-RSA-and-AES-blob"
}
```

The response reports the ingestion outcome. Depending on validation and
duplicate detection, outcomes include `SETTLED`, `DUPLICATE_DROPPED`, or
`INVALID`.

Example response:

``` json
{
  "outcome": "SETTLED",
  "packetHash": "a3f8c9...",
  "reason": null,
  "transactionId": 42
}
```

### H2 console

The demo uses an in-memory H2 database. The console is available at:

``` text
http://localhost:8080/h2-console
```

Example connection settings:

``` text
JDBC URL: jdbc:h2:mem:upimesh
Username: sa
Password: (leave blank)
```

## Project Structure

The main application code is organized into domain, cryptography,
service, controller, and configuration packages.

``` text
upi-offline-mesh/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── README.md
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── application.properties
    │   │   └── templates/
    │   │       └── dashboard.html
    │   └── java/com/demo/upimesh/
    │       ├── UpiMeshApplication.java
    │       ├── model/
    │       │   ├── Account.java
    │       │   ├── AccountRepository.java
    │       │   ├── Transaction.java
    │       │   ├── TransactionRepository.java
    │       │   ├── MeshPacket.java
    │       │   └── PaymentInstruction.java
    │       ├── crypto/
    │       │   ├── ServerKeyHolder.java
    │       │   └── HybridCryptoService.java
    │       ├── service/
    │       │   ├── DemoService.java
    │       │   ├── VirtualDevice.java
    │       │   ├── MeshSimulatorService.java
    │       │   ├── IdempotencyService.java
    │       │   ├── SettlementService.java
    │       │   └── BridgeIngestionService.java
    │       ├── controller/
    │       │   ├── ApiController.java
    │       │   └── DashboardController.java
    │       └── config/
    │           └── AppConfig.java
    └── test/
        └── java/com/demo/upimesh/
            └── IdempotencyConcurrencyTest.java
```

## Limitations and Production Considerations

This project is a local educational prototype. Several components are
intentionally simulated or simplified.

  -----------------------------------------------------------------------
  Current demo implementation         Production consideration
  ----------------------------------- -----------------------------------
  H2 in-memory database               Use a durable database such as
                                      PostgreSQL or MySQL with backups
                                      and an appropriate availability
                                      strategy.

  JVM-local `ConcurrentHashMap`       Use shared idempotency coordination
  idempotency cache                   across application instances, with
                                      database constraints as a
                                      safeguard.

  RSA key pair generated at           Use managed key storage such as AWS
  application startup                 KMS or an HSM, with key rotation
                                      and lifecycle management.

  Server-side sender simulation       Implement the sender-side
                                      cryptographic flow in a mobile
                                      application with secure key
                                      storage.

  Software-simulated mesh             Implement and validate real device
                                      communication, such as BLE GATT or
                                      Wi-Fi Direct.

  Demo settlement ledger              Integrate with authorized
                                      banking/payment infrastructure and
                                      its reconciliation processes.

  No production bridge authentication Add strong bridge identity, request
                                      authentication, authorization, and
                                      transport security.

  Demo accounts and PIN-related       Implement real identity
  fields                              verification, secure credential
                                      handling, and risk controls.

  Local dashboard and H2 console      Restrict administrative and
                                      database access appropriately.

  No production rate limiting or      Add rate limits, structured logs,
  monitoring                          metrics, tracing, alerting, and
                                      abuse detection.
  -----------------------------------------------------------------------

## Security and Conceptual Limitations

The prototype demonstrates selected backend and cryptographic
mechanisms. It does not solve every problem involved in real offline
payments.

1.  **Funds availability:** The receiver cannot know whether the sender
    has sufficient funds until the backend receives and processes the
    payment. A displayed offline payment is therefore a pending
    instruction, not proof of final settlement.
2.  **Offline double spending:** A sender could create multiple payment
    instructions while disconnected. Backend processing can reject later
    instructions when funds are no longer available, but this does not
    provide an offline guarantee that funds were reserved.
3.  **Real-world device connectivity:** Reliable background Bluetooth
    communication has platform, permission, battery, and
    operating-system constraints. The simulator does not address these
    hardware and mobile-platform challenges.
4.  **Privacy and metadata:** Encryption protects payment contents from
    intermediary devices, but carrying packets can still expose metadata
    about communication activity.
5.  **Regulatory and payment-network integration:** This application is
    not connected to UPI, NPCI, banks, or a production payment switch.
    Any real deployment would require the relevant security, compliance,
    operational, and institutional approvals.

