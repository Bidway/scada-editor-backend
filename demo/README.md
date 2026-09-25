# Демо редактора мнемосхем на Linux-сервере

Только редактор: компоненты, состояния, шаблоны, история версий. Базы каналов, монитора,
runtime, скриптов на бэке, Kafka и Redis нет — пункты меню «Монитор», «База каналов»,
«Автоматизация», «Логирование» будут отвечать ошибками, выбор тега в редакторе пустой.

Что крутится на сервере, все порты кроме 80/443 — только на `127.0.0.1`:

| Процесс | Порт | Что это |
|---|---|---|
| PostgreSQL | 5432 | база `savushkin`, схемы `auth` и `editor` создаются сами |
| `scada-auth` | 8081 | вход, JWT |
| `scada-editor` | 8083 | редактор |
| `scada-gateway` | 8080 | проверка токена |
| `scada-frontend` | 3000 | интерфейс (Next.js) |
| nginx | 80 → 443 | HTTPS снаружи |

HTTPS обязателен: без него браузер не сохраняет cookie входа (флаг `Secure`), и вход
молча не срабатывает. Сертификат самоподписанный — при первом заходе браузер покажет
предупреждение, его надо пропустить («Дополнительно → Перейти на сайт»).

Команды рассчитаны на Ubuntu 22.04/24.04 или Debian 12. Везде, где встречается
`203.0.113.10`, подставь IP сервера.

---

## 0. Собрать архив (на машине разработчика, Windows)

Из корня репозитория бэкенда, на ветке `demo/editor-standalone`. Нужны JDK 17 и Docker
Desktop (Docker — только для сборки фронта), рядом должен лежать репозиторий фронта
`..\scada-editor-frontend`.

```powershell
.\demo\build-package.ps1
scp .\demo\dist\scada-demo.tar.gz user@203.0.113.10:/tmp/
```

Готовые jar и фронт едут архивом, а не через git: jar `editor` весит больше 150 МБ, а
GitHub не принимает файлы больше 100 МБ.

## 0а. Ветка на сервере (по желанию)

Для запуска достаточно архива: README и конфиги лежат в нём же. Если на сервере уже есть
старый клон репозитория (`scada-editor-ms`), ветку можно подтянуть туда, чтобы README был
под рукой. История репозитория переписывалась (10.08 и 16.09.2026), поэтому **не делай
`git pull` на старой ветке** — будут конфликты на ровном месте. Только `fetch` и `switch`:

```bash
cd ~/scada-editor-ms          # путь к старому клону
git status                    # если есть свои правки — сохрани их: git stash
git fetch origin
git switch demo/editor-standalone
less demo/README.md
```

## 1. Проверить, что стоит на сервере

```bash
java -version      # нужна 17 или новее
node -v            # нужна 20 или новее
psql --version
nginx -v
```

Доставить недостающее:

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless postgresql nginx openssl

# Node.js 20, если его нет или он старее 20:
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

## 2. Распаковать

```bash
sudo useradd --system --home /opt/scada-demo --shell /usr/sbin/nologin scada
sudo mkdir -p /opt/scada-demo/data/recipes
sudo tar -xzf /tmp/scada-demo.tar.gz -C /opt/scada-demo
sudo chown -R scada:scada /opt/scada-demo
ls /opt/scada-demo      # auth.jar editor.jar gateway.jar frontend server README.md data
```

## 3. База данных

Придумай пароль базы и подставь вместо `ПАРОЛЬ_БАЗЫ` (здесь и в шаге 4):

```bash
sudo systemctl enable --now postgresql
sudo -u postgres psql -c "CREATE USER scada WITH PASSWORD 'ПАРОЛЬ_БАЗЫ';"
sudo -u postgres psql -c "CREATE DATABASE savushkin OWNER scada;"
```

Таблицы создавать не нужно — сервисы создадут их сами при первом запуске.

## 4. Настройки

```bash
cd /opt/scada-demo
sudo cp server/scada.env.example scada.env
sudo sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=ПАРОЛЬ_БАЗЫ/" scada.env
sudo sed -i "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/" scada.env
sudo chown scada:scada scada.env
sudo chmod 600 scada.env
sudo grep -E "DB_PASSWORD|JWT_SECRET" scada.env    # не должно остаться CHANGE_ME
```

## 5. Запустить сервисы

```bash
sudo cp /opt/scada-demo/server/systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now scada-auth scada-editor
sleep 40
sudo systemctl enable --now scada-gateway scada-frontend
sleep 20
systemctl --no-pager status scada-auth scada-editor scada-gateway scada-frontend | grep -E "●|Active"
```

У всех четырёх должно быть `active (running)`. Проверка изнутри:

```bash
curl -s -o /dev/null -w "front %{http_code}\n" http://127.0.0.1:3000/login     # 200
curl -s -o /dev/null -w "gateway %{http_code}\n" http://127.0.0.1:8080/api/editor/projects   # 401 — это норма, без токена
```

## 6. HTTPS через nginx

```bash
IP=203.0.113.10
sudo mkdir -p /etc/ssl/scada-demo
sudo openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout /etc/ssl/scada-demo/key.pem -out /etc/ssl/scada-demo/cert.pem \
  -subj "/CN=$IP" -addext "subjectAltName=IP:$IP"

sudo rm -f /etc/nginx/sites-enabled/default
sudo cp /opt/scada-demo/server/nginx/scada-demo.conf /etc/nginx/sites-available/
sudo ln -sf /etc/nginx/sites-available/scada-demo.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

Если включён файрвол `ufw` (`sudo ufw status`):

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

## 7. Открыть

`https://203.0.113.10` → пропустить предупреждение о сертификате → «Зарегистрироваться»
→ после входа — «Редактор схем» в меню. Каждый тестер регистрируется сам.

В редакторе: кнопка «Проект» на панели → создать проект → создать схему. Элемент
добавляется кликом в палитре и затем кликом по холсту. Состояния — справа, вкладка
«Состояния» у выделенного элемента. Сохранение — кнопка с дискетой.

---

## Обслуживание

```bash
# логи одного сервиса (Ctrl+C — выход)
sudo journalctl -u scada-editor -f

# перезапуск / остановка всего
sudo systemctl restart scada-auth scada-editor scada-gateway scada-frontend
sudo systemctl stop scada-frontend scada-gateway scada-editor scada-auth

# обновить на новую сборку: скопировать новый архив в /tmp, затем
sudo systemctl stop scada-frontend scada-gateway scada-editor scada-auth
sudo rm -rf /opt/scada-demo/frontend
sudo tar -xzf /tmp/scada-demo.tar.gz -C /opt/scada-demo
sudo chown -R scada:scada /opt/scada-demo
sudo systemctl start scada-auth scada-editor && sleep 40 && sudo systemctl start scada-gateway scada-frontend

# стереть все данные (пользователей, проекты) и начать с чистой базы
sudo systemctl stop scada-frontend scada-gateway scada-editor scada-auth
sudo -u postgres psql -c "DROP DATABASE savushkin;" -c "CREATE DATABASE savushkin OWNER scada;"
sudo systemctl start scada-auth scada-editor && sleep 40 && sudo systemctl start scada-gateway scada-frontend
```

## Если не работает

| Симптом | Где смотреть |
|---|---|
| Сервис в `failed` | `sudo journalctl -u scada-<имя> -n 100 --no-pager` |
| `password authentication failed` в логе | пароль в `scada.env` не совпадает с шагом 3 |
| Регистрация проходит, но снова выкидывает на вход | зашли по `http://`, а не `https://` |
| `502 Bad Gateway` от nginx | не запущен `scada-frontend` |
| Редактор пишет ошибку при сохранении | `journalctl -u scada-gateway` и `-u scada-editor` |
| Сервис падает с `OutOfMemoryError` | поднять `-Xmx` в `/etc/systemd/system/scada-*.service`, затем `daemon-reload` и `restart` |

Порядок запуска важен только в первый раз: `auth` и `editor` создают таблицы, gateway
и фронт от них зависят. Дальше systemd поднимает всё сам после перезагрузки сервера.
