const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validSourcePayload(uid, created = new Date(Date.now() - 5000)) {
  return {
    userId: uid,
    name: "Mercor",
    url: "https://work.mercor.com/explore",
    category: "AI_TALENT",
    enabled: true,
    pollIntervalSeconds: 15,
    lastStatus: "IDLE",
    lastLatencyMs: 0,
    lastJobCount: 0,
    createdAt: created,
    updatedAt: created,
  };
}

function validFilterPayload(uid, created = new Date(Date.now() - 5000)) {
  return {
    userId: uid,
    name: "High-Pay AI & Kotlin",
    enabled: true,
    matchMode: "ANY",
    includeKeywords: ["Kotlin", "AI Trainer", "Android"],
    excludeKeywords: ["Intern", "Unpaid"],
    workMode: "REMOTE",
    minHourlyRate: 50,
    targetSourceIds: [],
    instantPush: true,
    createdAt: created,
    updatedAt: created,
  };
}

function validAlertPayload(uid, created = new Date(Date.now() - 5000)) {
  return {
    userId: uid,
    sourceId: "src_mercor",
    sourceName: "Mercor",
    fingerprint: "fp_abc123def456",
    title: "Senior Android & AI Systems Engineer",
    company: "Mercor",
    location: "Remote",
    compensation: "$110/hr",
    jobUrl: "https://work.mercor.com/jobs/listing-123",
    matchedFilterNames: ["High-Pay AI & Kotlin"],
    detectionLatencyMs: 142,
    freshAlert: true,
    bookmarked: false,
    read: false,
    applied: false,
    createdAt: created,
    updatedAt: created,
  };
}

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("sources").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("filters").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("alerts").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("sources").doc("src_1").set(validSourcePayload(ALICE_UID))
  );
});

test("Sources: owner CRUD, telemetry partial updates, and strict validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sourcesCol = aliceDb.collection("users").doc(ALICE_UID).collection("sources");

  // Empty list query succeeds
  await assertSucceeds(sourcesCol.get());

  // Valid create succeeds
  await assertSucceeds(sourcesCol.doc("src_mercor").set(validSourcePayload(ALICE_UID)));

  // Config partial update succeeds
  await assertSucceeds(
    sourcesCol.doc("src_mercor").update({
      pollIntervalSeconds: 10,
      enabled: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Telemetry partial update succeeds (including optional ETag/lastCheckedAt)
  await assertSucceeds(
    sourcesCol.doc("src_mercor").update({
      lastStatus: "LIVE",
      lastLatencyMs: 128,
      lastJobCount: 24,
      etag: "\"v1-mercor\"",
      lastCheckedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject non-HTTPS URL
  await assertFails(
    sourcesCol.doc("src_http").set({
      ...validSourcePayload(ALICE_UID),
      url: "http://insecure.example.com/jobs",
    })
  );

  // Reject ghost field / @DocumentId 'id' field
  await assertFails(
    sourcesCol.doc("src_ghost").set({
      ...validSourcePayload(ALICE_UID),
      id: "src_ghost",
    })
  );

  // Reject future timestamp
  await assertFails(
    sourcesCol.doc("src_future").set({
      ...validSourcePayload(ALICE_UID),
      createdAt: new Date(Date.now() + 60000),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    sourcesCol.doc("src_mercor").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Delete succeeds for owner
  await assertSucceeds(sourcesCol.doc("src_mercor").delete());
});

test("Filters: owner create, update, list, and invalid payload rejection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const filtersCol = aliceDb.collection("users").doc(ALICE_UID).collection("filters");

  await assertSucceeds(filtersCol.get());
  await assertSucceeds(filtersCol.doc("flt_1").set(validFilterPayload(ALICE_UID)));

  await assertSucceeds(
    filtersCol.doc("flt_1").update({
      minHourlyRate: 75,
      instantPush: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject invalid matchMode
  await assertFails(
    filtersCol.doc("flt_bad").set({
      ...validFilterPayload(ALICE_UID),
      matchMode: "XOR",
    })
  );

  // Reject ghost field on update
  await assertFails(
    filtersCol.doc("flt_1").update({
      adminOverride: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Alerts: owner create, bookmark/applied update, and immutable field protection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const alertsCol = aliceDb.collection("users").doc(ALICE_UID).collection("alerts");

  await assertSucceeds(alertsCol.get());
  await assertSucceeds(alertsCol.doc("alert_1").set(validAlertPayload(ALICE_UID)));

  // User bookmark/read/applied update succeeds
  await assertSucceeds(
    alertsCol.doc("alert_1").update({
      bookmarked: true,
      read: true,
      applied: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject modifying immutable title or fingerprint
  await assertFails(
    alertsCol.doc("alert_1").update({
      title: "Tampered Title",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject javascript: jobUrl
  await assertFails(
    alertsCol.doc("alert_xss").set({
      ...validAlertPayload(ALICE_UID),
      jobUrl: "javascript:alert(1)",
    })
  );
});

test("Cross-user isolation: Bob cannot read, write, or delete Alice's data", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sources").doc("src_1").set(validSourcePayload(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("filters").doc("flt_1").set(validFilterPayload(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("alerts").doc("alt_1").set(validAlertPayload(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sources").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sources").doc("src_1").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("sources").doc("src_1").update({
      enabled: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sources").doc("src_1").delete());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("filters").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("alerts").get());
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("sources").doc("spoof").set(validSourcePayload(ALICE_UID))
  );
});
