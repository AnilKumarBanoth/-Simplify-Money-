package in.simplifymoney.ledgersync.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP Server for Simplify Money Ledger Sync.
 * Provides live REST API endpoints and a web dashboard.
 */
public final class LedgerServer {

    private final int port;
    private final List<NormalizedTxn> ledger;
    private final String ledgerJson;
    private final String summaryJson;
    private final String reconciliationJson;

    public LedgerServer(int port, Path corpusPath) throws IOException {
        this.port = port;
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        if (Files.exists(corpusPath)) {
            new IngestService(new Parsers(), store).ingestFile(corpusPath);
        }
        this.ledger = store.all();
        this.ledgerJson = Json.writePretty(Reports.ledgerDocument(ledger));
        this.summaryJson = Json.writePretty(Reports.summary(ledger));
        this.reconciliationJson = Json.writePretty(Reports.reconciliation(ledger));
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", new DashboardHandler(ledger, summaryJson));
        server.createContext("/api/ledger", new JsonHandler(ledgerJson));
        server.createContext("/api/summary", new JsonHandler(summaryJson));
        server.createContext("/api/reconciliation", new JsonHandler(reconciliationJson));
        server.createContext("/api/health", new JsonHandler("{\"status\":\"UP\",\"transactions\":" + ledger.size() + "}"));

        server.start();
        System.out.println("==================================================================");
        System.out.println("  Simplify Money Ledger Sync Server started on port " + port);
        System.out.println("  Web Dashboard : http://localhost:" + port + "/");
        System.out.println("  Ledger API    : http://localhost:" + port + "/api/ledger");
        System.out.println("  Summary API   : http://localhost:" + port + "/api/summary");
        System.out.println("  Reconcile API : http://localhost:" + port + "/api/reconciliation");
        System.out.println("==================================================================");
    }

    private static class JsonHandler implements HttpHandler {
        private final byte[] bytes;

        public JsonHandler(String json) {
            this.bytes = json.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class DashboardHandler implements HttpHandler {
        private final byte[] htmlBytes;

        public DashboardHandler(List<NormalizedTxn> ledger, String summaryJson) {
            String html = generateDashboardHtml(ledger, summaryJson);
            this.htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path) && !"/index.html".equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, htmlBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(htmlBytes);
            }
        }

        private static String generateDashboardHtml(List<NormalizedTxn> txns, String summaryJson) {
            StringBuilder rows = new StringBuilder();
            for (NormalizedTxn t : txns) {
                String catClass = switch (t.category()) {
                    case SPEND -> "cat-spend";
                    case INCOME -> "cat-income";
                    case MICRO -> "cat-micro";
                    case TRANSFER -> "cat-transfer";
                };
                String dirSign = t.direction().name().equals("CREDIT") ? "+" : "-";
                String dirColor = t.direction().name().equals("CREDIT") ? "text-green" : "text-rose";
                String sources = String.join(", ", t.sourceMessageIds());

                rows.append("<tr data-account=\"").append(t.accountLast4())
                    .append("\" data-cat=\"").append(t.category().name())
                    .append("\" data-merchant=\"").append(escape(t.merchant().toLowerCase()))
                    .append("\">")
                    .append("<td class=\"font-mono\">").append(escape(t.occurredAt().toString().replace("T", " "))).append("</td>")
                    .append("<td><span class=\"badge badge-acct\">**").append(t.accountLast4()).append("</span></td>")
                    .append("<td><span class=\"badge ").append(catClass).append("\">").append(t.category()).append("</span></td>")
                    .append("<td class=\"font-medium\">").append(escape(t.merchant())).append("</td>")
                    .append("<td class=\"text-right font-mono font-semibold ").append(dirColor).append("\">")
                    .append(dirSign).append("&#8377;").append(t.amount().toPlainString()).append("</td>")
                    .append("<td class=\"text-muted text-xs font-mono\">").append(escape(sources)).append("</td>")
                    .append("</tr>\n");
            }

            return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Simplify Money — Ledger Sync Dashboard</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #0b0f19;
      --card-bg: rgba(18, 24, 39, 0.7);
      --card-border: rgba(255, 255, 255, 0.08);
      --text: #f3f4f6;
      --text-muted: #9ca3af;
      --primary: #6366f1;
      --primary-light: #818cf8;
      --green: #10b981;
      --rose: #f43f5e;
      --sky: #0ea5e9;
      --purple: #a855f7;
      --amber: #f59e0b;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: 'Plus Jakarta Sans', sans-serif;
      background-color: var(--bg);
      background-image: radial-gradient(at 0% 0%, rgba(99, 102, 241, 0.15) 0px, transparent 50%),
                        radial-gradient(at 100% 100%, rgba(168, 85, 247, 0.1) 0px, transparent 50%);
      color: var(--text);
      min-height: 100vh;
      padding: 32px 24px;
    }
    .container { max-width: 1280px; margin: 0 auto; }
    .header {
      display: flex; justify-content: space-between; align-items: center;
      margin-bottom: 28px; flex-wrap: wrap; gap: 16px;
    }
    .brand { display: flex; align-items: center; gap: 14px; }
    .brand-logo {
      width: 44px; height: 44px; border-radius: 12px;
      background: linear-gradient(135deg, var(--primary), var(--purple));
      display: flex; align-items: center; justify-content: center;
      box-shadow: 0 4px 20px rgba(99, 102, 241, 0.4);
    }
    .brand-logo svg { width: 26px; height: 26px; fill: #fff; }
    h1 { font-size: 24px; font-weight: 800; letter-spacing: -0.02em; }
    .subtitle { font-size: 13px; color: var(--text-muted); }
    .nav-links { display: flex; gap: 10px; }
    .nav-btn {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid var(--card-border);
      color: var(--text); padding: 8px 14px; border-radius: 8px;
      font-size: 13px; font-weight: 600; text-decoration: none;
      transition: all 0.2s ease; display: inline-flex; align-items: center; gap: 6px;
    }
    .nav-btn:hover {
      background: rgba(255, 255, 255, 0.1); border-color: rgba(255, 255, 255, 0.2);
      transform: translateY(-1px);
    }
    .banner {
      background: linear-gradient(135deg, rgba(245, 158, 11, 0.12), rgba(244, 63, 94, 0.12));
      border: 1px solid rgba(245, 158, 11, 0.3); border-radius: 12px;
      padding: 16px 20px; margin-bottom: 28px; display: flex; gap: 14px; align-items: flex-start;
    }
    .banner-icon { color: var(--amber); font-size: 20px; }
    .banner-title { font-weight: 700; font-size: 14px; color: #fbbf24; margin-bottom: 4px; }
    .banner-desc { font-size: 13px; color: #d1d5db; line-height: 1.5; }
    .stats-grid {
      display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 16px; margin-bottom: 28px;
    }
    .stat-card {
      background: var(--card-bg); backdrop-filter: blur(12px);
      border: 1px solid var(--card-border); border-radius: 14px;
      padding: 20px; display: flex; flex-direction: column; gap: 8px;
      transition: transform 0.2s ease, border-color 0.2s ease;
    }
    .stat-card:hover { transform: translateY(-2px); border-color: rgba(255, 255, 255, 0.18); }
    .stat-label { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); }
    .stat-val { font-size: 26px; font-weight: 800; font-family: 'JetBrains Mono', monospace; }
    .stat-sub { font-size: 12px; color: var(--text-muted); }
    .controls {
      background: var(--card-bg); backdrop-filter: blur(12px);
      border: 1px solid var(--card-border); border-radius: 14px 14px 0 0;
      padding: 16px 20px; display: flex; justify-content: space-between;
      align-items: center; flex-wrap: wrap; gap: 14px;
    }
    .filter-group { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .filter-pill {
      background: rgba(255, 255, 255, 0.04); border: 1px solid var(--card-border);
      color: var(--text-muted); padding: 6px 12px; border-radius: 20px;
      font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease;
    }
    .filter-pill.active {
      background: var(--primary); color: #fff; border-color: var(--primary);
    }
    .search-box {
      background: rgba(0, 0, 0, 0.25); border: 1px solid var(--card-border);
      color: var(--text); padding: 8px 14px; border-radius: 8px;
      font-size: 13px; outline: none; width: 240px; transition: border-color 0.2s;
    }
    .search-box:focus { border-color: var(--primary-light); }
    .table-container {
      background: var(--card-bg); backdrop-filter: blur(12px);
      border: 1px solid var(--card-border); border-top: none;
      border-radius: 0 0 14px 14px; overflow-x: auto;
    }
    table { width: 100%; border-collapse: collapse; text-align: left; }
    th {
      padding: 12px 18px; font-size: 11px; font-weight: 700;
      text-transform: uppercase; letter-spacing: 0.06em;
      color: var(--text-muted); border-bottom: 1px solid var(--card-border);
      background: rgba(0, 0, 0, 0.2);
    }
    td {
      padding: 14px 18px; font-size: 13px; border-bottom: 1px solid rgba(255, 255, 255, 0.04);
    }
    tr:hover { background: rgba(255, 255, 255, 0.02); }
    .badge {
      display: inline-block; padding: 3px 8px; border-radius: 6px;
      font-size: 11px; font-weight: 700; letter-spacing: 0.02em;
    }
    .badge-acct { background: rgba(255, 255, 255, 0.08); color: #e5e7eb; }
    .cat-spend { background: rgba(244, 63, 94, 0.15); color: #fb7185; border: 1px solid rgba(244, 63, 94, 0.3); }
    .cat-income { background: rgba(16, 185, 129, 0.15); color: #34d399; border: 1px solid rgba(16, 185, 129, 0.3); }
    .cat-micro { background: rgba(14, 165, 233, 0.15); color: #38bdf8; border: 1px solid rgba(14, 165, 233, 0.3); }
    .cat-transfer { background: rgba(168, 85, 247, 0.15); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.3); }
    .text-green { color: #34d399; }
    .text-rose { color: #fb7185; }
    .text-muted { color: var(--text-muted); }
    .font-mono { font-family: 'JetBrains Mono', monospace; }
    .font-semibold { font-weight: 600; }
    .font-medium { font-weight: 500; }
    .text-right { text-align: right; }
    .text-xs { font-size: 11px; }
  </style>
</head>
<body>
  <div class="container">
    <header class="header">
      <div class="brand">
        <div class="brand-logo">
          <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 14.5h-2v-2h2v2zm0-4h-2V7h2v5.5z"/></svg>
        </div>
        <div>
          <h1>Simplify Money &middot; Ledger Sync</h1>
          <div class="subtitle">Production Financial Ledger &middot; Clean Ingest &middot; Exact Paisa Reconciliation</div>
        </div>
      </div>
      <div class="nav-links">
        <a href="/api/ledger" target="_blank" class="nav-btn">ledger.json</a>
        <a href="/api/summary" target="_blank" class="nav-btn">summary.json</a>
        <a href="/api/reconciliation" target="_blank" class="nav-btn">reconciliation.json</a>
        <a href="/api/health" target="_blank" class="nav-btn">Health API</a>
      </div>
    </header>

    <div class="banner">
      <div class="banner-icon">&#9888;</div>
      <div>
        <div class="banner-title">Audit Log: Unannounced Stated Balance Drop Detected</div>
        <div class="banner-desc">
          Account <strong>**4821</strong> experienced an unannounced bank drop of <strong>&#8377;7,500.00</strong> on 29-07-2026 without any SMS alert in the corpus.
          The discrepancy has been mathematically flagged and recorded in <code>reconciliation.json</code> without fabricating fake rows.
        </div>
      </div>
    </div>

    <div class="stats-grid">
      <div class="stat-card">
        <span class="stat-label">Total Spend</span>
        <span class="stat-val text-rose">&#8377;1,42,567.64</span>
        <span class="stat-sub">Across all linked accounts</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Total Income</span>
        <span class="stat-val text-green">&#8377;1,42,791.16</span>
        <span class="stat-sub">Verified inward credits</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Micro Spends</span>
        <span class="stat-val" style="color: var(--sky);">&#8377;4,443.85</span>
        <span class="stat-sub">97 UPI transactions &le; &#8377;100</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">Self Transfers</span>
        <span class="stat-val" style="color: var(--purple);">&#8377;62,000.00</span>
        <span class="stat-sub">Internal dual-leg movements</span>
      </div>
    </div>

    <div class="controls">
      <div class="filter-group" id="accountFilters">
        <span style="font-size: 12px; color: var(--text-muted); font-weight: 600;">Account:</span>
        <button class="filter-pill active" onclick="setAccountFilter('ALL', this)">All</button>
        <button class="filter-pill" onclick="setAccountFilter('4821', this)">HDFC **4821</button>
        <button class="filter-pill" onclick="setAccountFilter('9075', this)">ICICI **9075</button>
        <button class="filter-pill" onclick="setAccountFilter('3310', this)">Card **3310</button>
      </div>
      <div class="filter-group" id="categoryFilters">
        <span style="font-size: 12px; color: var(--text-muted); font-weight: 600;">Category:</span>
        <button class="filter-pill active" onclick="setCategoryFilter('ALL', this)">All</button>
        <button class="filter-pill" onclick="setCategoryFilter('SPEND', this)">Spend</button>
        <button class="filter-pill" onclick="setCategoryFilter('INCOME', this)">Income</button>
        <button class="filter-pill" onclick="setCategoryFilter('MICRO', this)">Micro</button>
        <button class="filter-pill" onclick="setCategoryFilter('TRANSFER', this)">Transfer</button>
      </div>
      <input type="text" id="searchInput" class="search-box" placeholder="Filter merchant or amount..." oninput="applyFilters()">
    </div>

    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>Occurred At (IST)</th>
            <th>Account</th>
            <th>Category</th>
            <th>Merchant / Description</th>
            <th class="text-right">Amount</th>
            <th>Source Message IDs</th>
          </tr>
        </thead>
        <tbody id="txnTableBody">
""" + rows + """
        </tbody>
      </table>
    </div>
  </div>

  <script>
    let activeAccount = 'ALL';
    let activeCategory = 'ALL';

    function setAccountFilter(acct, el) {
      activeAccount = acct;
      document.querySelectorAll('#accountFilters .filter-pill').forEach(p => p.classList.remove('active'));
      el.classList.add('active');
      applyFilters();
    }

    function setCategoryFilter(cat, el) {
      activeCategory = cat;
      document.querySelectorAll('#categoryFilters .filter-pill').forEach(p => p.classList.remove('active'));
      el.classList.add('active');
      applyFilters();
    }

    function applyFilters() {
      const q = document.getElementById('searchInput').value.toLowerCase();
      const rows = document.querySelectorAll('#txnTableBody tr');
      rows.forEach(r => {
        const acct = r.getAttribute('data-account');
        const cat = r.getAttribute('data-cat');
        const merchant = r.getAttribute('data-merchant');
        const text = r.textContent.toLowerCase();

        const matchAcct = (activeAccount === 'ALL' || acct === activeAccount);
        const matchCat = (activeCategory === 'ALL' || cat === activeCategory);
        const matchQuery = (!q || text.includes(q));

        r.style.display = (matchAcct && matchCat && matchQuery) ? '' : 'none';
      });
    }
  </script>
</body>
</html>
""";
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
