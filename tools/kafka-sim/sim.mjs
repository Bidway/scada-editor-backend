// Лёгкий симулятор ПЛК для тестирования выполнения команд.
//
// Говорит на нашем чистом строковом контракте (см. docs/FRONTEND_RUNTIME_INTEGRATION.md,
// docs/SCRIPTS_PLAN.md): тег = idNode (путь узла), значение — как есть.
//
//   • Телеметрия: периодически публикует значения тегов сцены в `scada.tags`
//     (key = idNode, тело { idNode, value, ts }). runtime читает поле `value`.
//   • Команды: слушает `scada-commands`, применяет { idNode, value } к своему
//     состоянию, СРАЗУ возвращает новое значение телеметрией (замыкает цикл),
//     и пишет результат в `scada-command-results`.
//
// Запуск:  cd tools/kafka-sim && npm install && npm start
// Оверрайды через env: KAFKA_BROKER, TAGS_TOPIC, COMMANDS_TOPIC, RESULTS_TOPIC, PERIOD_MS.

import { Kafka, logLevel } from "kafkajs";

const BROKER = process.env.KAFKA_BROKER || "localhost:9092";
const TAGS_TOPIC = process.env.TAGS_TOPIC || "scada.tags";
const COMMANDS_TOPIC = process.env.COMMANDS_TOPIC || "scada-commands";
const RESULTS_TOPIC = process.env.RESULTS_TOPIC || "scada-command-results";
const PERIOD_MS = Number(process.env.PERIOD_MS || 2000);

// Теги сцены BN1_MCA1 (idNode = путь узла = Kafka-key телеметрии).
const V2 = "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST"; // Клапан2 ST (цель кнопки)
const V1 = "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V10.ST"; // Клапан1 ST
const M = "Барановичи-1.BN1_MCA1.V_M_1.LINE1V0.M"; // Танк fullness (число)

/** Текущее состояние тегов. Команды его меняют, телеметрия — публикует. */
const state = new Map([
  [V2, false],
  [V1, false],
  [M, 0],
]);

const kafka = new Kafka({ clientId: "scada-sim", brokers: [BROKER], logLevel: logLevel.NOTHING });
const producer = kafka.producer();
const consumer = kafka.consumer({ groupId: "scada-sim" });

const shortName = (idNode) => idNode.split(".").pop();

async function publish(idNode, value) {
  await producer.send({
    topic: TAGS_TOPIC,
    messages: [{ key: idNode, value: JSON.stringify({ type: "TELEMETRY", idNode, tagName: idNode, value, ts: Date.now() }) }],
  });
}

async function applyCommand(raw) {
  let cmd;
  try {
    cmd = JSON.parse(raw);
  } catch {
    console.warn("[cmd] битый JSON, пропуск");
    return;
  }
  // Наш контракт — idNode; tagName терпим на всякий случай.
  const idNode = cmd.idNode ?? cmd.tagName;
  if (!idNode) {
    console.warn("[cmd] нет idNode/tagName, пропуск");
    return;
  }
  const value = cmd.value;
  state.set(idNode, value);
  console.log(`[cmd] ◄ ${shortName(idNode)} := ${JSON.stringify(value)}  (commandId=${cmd.commandId ?? "-"})`);

  await publish(idNode, value); // сразу вернуть новое значение телеметрией — цикл замкнут
  await producer.send({
    topic: RESULTS_TOPIC,
    messages: [{ key: idNode, value: JSON.stringify({
      commandId: cmd.commandId ?? null, idNode, success: true, appliedValue: value, ts: Date.now(),
    }) }],
  });
  console.log(`[cmd] ► ${shortName(idNode)} применён и возвращён телеметрией`);
}

async function main() {
  await producer.connect();
  await consumer.connect();
  await consumer.subscribe({ topic: COMMANDS_TOPIC, fromBeginning: false });

  console.log(`▶ scada-sim: брокер ${BROKER}`);
  console.log(`  телеметрия → ${TAGS_TOPIC} (каждые ${PERIOD_MS} мс)`);
  console.log(`  команды    ← ${COMMANDS_TOPIC}`);
  console.log(`  результаты → ${RESULTS_TOPIC}`);
  console.log(`  теги сцены: ${[...state.keys()].map(shortName).join(", ")}`);

  await consumer.run({ eachMessage: async ({ message }) => applyCommand(message.value?.toString() ?? "") });

  // Периодическая телеметрия. Клапаны держат состояние (меняются только командой);
  // fullness плавно дрейфует 0→100→0, чтобы прогресс-бар «жил».
  setInterval(async () => {
    let m = state.get(M);
    m = m >= 100 ? 0 : Math.round((m + 3.5) * 10) / 10;
    state.set(M, m);
    for (const [idNode, value] of state) await publish(idNode, value);
  }, PERIOD_MS);
}

async function shutdown() {
  try {
    await consumer.disconnect();
    await producer.disconnect();
  } finally {
    process.exit(0);
  }
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

main().catch((e) => {
  console.error("sim упал:", e);
  process.exit(1);
});
