package edu.library;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Plain Java web server with direct JDBC access. No Spring or servlet container is used. */
public class App {
    private static final String DB_URL = env("LIBRARY_DB_URL", "jdbc:mysql://127.0.0.1:3306/library_db?serverTimezone=UTC");
    private static final String DB_USER = env("LIBRARY_DB_USER", "root");
    private static final String DB_PASSWORD = env("LIBRARY_DB_PASSWORD", "");
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Flash> FLASHES = new ConcurrentHashMap<>();

    private record Session(String username, String role) {}
    private record Flash(String text, String type) {}
    private record Book(String isbn, String title, String author, String status) {}
    private record Loan(String isbn, String title, LocalDate borrowed, LocalDate due) {}

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        initializeDatabase();
        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", App::handle);
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();
        System.out.println("Library Management System is running at http://localhost:" + port);
        System.out.println("Demo librarian account: librarian / admin123");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS users (username VARCHAR(50) PRIMARY KEY, password VARCHAR(100) NOT NULL, role ENUM('librarian','student') NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS books (isbn VARCHAR(20) PRIMARY KEY, title VARCHAR(255) NOT NULL, author VARCHAR(255) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS borrowings (id INT AUTO_INCREMENT PRIMARY KEY, isbn VARCHAR(20) NOT NULL, username VARCHAR(50) NOT NULL, borrow_date DATE NOT NULL, due_date DATE NOT NULL, returned_date DATE DEFAULT NULL, FOREIGN KEY (isbn) REFERENCES books(isbn), FOREIGN KEY (username) REFERENCES users(username))");
            s.executeUpdate("INSERT IGNORE INTO users(username,password,role) VALUES('librarian','admin123','librarian')");
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/style.css")) { serveCss(exchange); return; }
            String sid = sessionId(exchange);
            Session session = SESSIONS.get(sid);
            if (path.equals("/logout")) { SESSIONS.remove(sid); FLASHES.remove(sid); redirect(exchange, "/"); return; }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String,String> form = parseForm(exchange);
                switch (path) {
                    case "/auth" -> auth(exchange, sid, form);
                    case "/add_book" -> librarianAction(exchange, sid, session, () -> addBook(form), "Book added successfully.");
                    case "/remove_book" -> librarianAction(exchange, sid, session, () -> removeBook(form.get("isbn")), "Book removed successfully.");
                    case "/borrow" -> studentAction(exchange, sid, session, () -> "Book borrowed. Due: " + borrow(form.get("isbn"), session.username()) + ".");
                    case "/return" -> studentAction(exchange, sid, session, () -> {
                        BigDecimal fine = returnBook(form.get("isbn"), session.username());
                        return "Book returned successfully." + (fine.signum() > 0 ? " Fine incurred: $" + fine + "." : " No fine.");
                    });
                    default -> send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                }
                return;
            }
            if (path.equals("/") || path.equals("/dashboard")) {
                if (session == null) sendHtml(exchange, loginPage(FLASHES.remove(sid)));
                else sendHtml(exchange, dashboardPage(session, FLASHES.remove(sid)));
                return;
            }
            send(exchange, 404, "text/plain; charset=utf-8", "Not found");
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Request could not be completed." : e.getMessage();
            sendHtml(exchange, errorPage(message));
        } finally { exchange.close(); }
    }

    private static String sessionId(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("LIBRARY_SESSION") && SESSIONS.containsKey(pair[1])) return pair[1];
        }
        String id = UUID.randomUUID().toString();
        exchange.getResponseHeaders().add("Set-Cookie", "LIBRARY_SESSION=" + id + "; Path=/; HttpOnly; SameSite=Lax");
        return id;
    }

    private static Map<String,String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String,String> result = new HashMap<>();
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static void auth(HttpExchange x, String sid, Map<String,String> form) throws IOException {
        try {
            String username = form.getOrDefault("username", "").trim();
            String password = form.getOrDefault("password", "");
            if ("register".equals(form.get("action"))) {
                if (username.isBlank() || password.isBlank()) throw new IllegalArgumentException("Enter a username and password.");
                try (Connection c=connect(); PreparedStatement p=c.prepareStatement("INSERT INTO users(username,password,role) VALUES(?,?,'student')")) {
                    p.setString(1,username); p.setString(2,password); p.executeUpdate();
                } catch (SQLIntegrityConstraintViolationException ex) { throw new IllegalArgumentException("Username already exists."); }
                flashRedirect(x,sid,"Registration successful. Please log in.","success","/"); return;
            }
            try (Connection c=connect(); PreparedStatement p=c.prepareStatement("SELECT role FROM users WHERE username=? AND password=?")) {
                p.setString(1,username); p.setString(2,password);
                try (ResultSet rs=p.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Invalid credentials.");
                    SESSIONS.put(sid,new Session(username,rs.getString(1)));
                }
            }
            redirect(x,"/dashboard");
        } catch (Exception e) { flashRedirect(x,sid,message(e),"error","/"); }
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
    @FunctionalInterface private interface StudentWork { String run() throws Exception; }
    private static void librarianAction(HttpExchange x,String sid,Session session,Work work,String success) throws IOException {
        if(session==null || !session.role().equals("librarian")){redirect(x,"/dashboard");return;}
        try{work.run();flashRedirect(x,sid,success,"success","/dashboard");}
        catch(Exception e){flashRedirect(x,sid,message(e),"error","/dashboard");}
    }
    private static void studentAction(HttpExchange x,String sid,Session session,StudentWork work) throws IOException {
        if(session==null || !session.role().equals("student")){redirect(x,"/dashboard");return;}
        try{flashRedirect(x,sid,work.run(),"success","/dashboard");}
        catch(Exception e){flashRedirect(x,sid,message(e),"error","/dashboard");}
    }
    private static String message(Exception e) { return e.getMessage()==null ? "Request could not be completed." : e.getMessage(); }
    private static void flashRedirect(HttpExchange x,String sid,String text,String type,String path) throws IOException { FLASHES.put(sid,new Flash(text,type)); redirect(x,path); }
    private static void redirect(HttpExchange x,String path) throws IOException { x.getResponseHeaders().set("Location",path); x.sendResponseHeaders(303,-1); }
    private static void sendHtml(HttpExchange x,String html) throws IOException { send(x,200,"text/html; charset=utf-8",html); }
    private static void send(HttpExchange x,int status,String contentType,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type",contentType); x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.sendResponseHeaders(status,bytes.length); x.getResponseBody().write(bytes);
    }
    private static void serveCss(HttpExchange x) throws IOException {
        try(InputStream in=App.class.getResourceAsStream("/static/style.css")) {
            if(in==null){send(x,404,"text/plain","Stylesheet not found");return;}
            send(x,200,"text/css; charset=utf-8",new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }

    private static void addBook(Map<String,String> form) throws SQLException {
        String title=form.getOrDefault("title","").trim(), author=form.getOrDefault("author","").trim(), isbn=form.getOrDefault("isbn","").trim();
        if(title.isBlank()||author.isBlank()||isbn.isBlank())throw new IllegalArgumentException("Complete all book fields.");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO books(isbn,title,author) VALUES(?,?,?)")){
            p.setString(1,isbn);p.setString(2,title);p.setString(3,author);p.executeUpdate();
        }catch(SQLIntegrityConstraintViolationException e){throw new IllegalArgumentException("Book with this ISBN already exists.");}
    }
    private static void removeBook(String isbn) throws SQLException {
        try(Connection c=connect()){
            try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM borrowings WHERE isbn=? AND returned_date IS NULL")){
                p.setString(1,isbn);try(ResultSet rs=p.executeQuery()){rs.next();if(rs.getInt(1)>0)throw new IllegalArgumentException("Cannot remove. Book is currently borrowed.");}
            }
            try(PreparedStatement p=c.prepareStatement("DELETE FROM books WHERE isbn=?")){p.setString(1,isbn);if(p.executeUpdate()==0)throw new IllegalArgumentException("Book not found.");}
            catch(SQLIntegrityConstraintViolationException e){throw new IllegalArgumentException("This book has borrowing history and cannot be removed.");}
        }
    }
    private static BigDecimal fines(String username) throws SQLException {
        BigDecimal total=BigDecimal.ZERO; LocalDate today=LocalDate.now();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT due_date FROM borrowings WHERE username=? AND returned_date IS NULL")){
            p.setString(1,username);try(ResultSet rs=p.executeQuery()){while(rs.next()){
                LocalDate due=rs.getDate(1).toLocalDate();if(today.isAfter(due))total=total.add(new BigDecimal("0.50").multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(due,today))));
            }}
        } return total.setScale(2);
    }
    private static LocalDate borrow(String isbn,String username) throws SQLException {
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement("SELECT isbn FROM books WHERE isbn=? FOR UPDATE")){
                    p.setString(1,isbn);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Book not found.");}
                }
                try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM borrowings WHERE isbn=? AND returned_date IS NULL")){
                    p.setString(1,isbn);try(ResultSet rs=p.executeQuery()){rs.next();if(rs.getInt(1)>0)throw new IllegalArgumentException("This copy is currently borrowed.");}
                }
                BigDecimal fine=fines(username);if(fine.signum()>0)throw new IllegalArgumentException("Outstanding fine of $"+fine+" must be paid before borrowing.");
                LocalDate today=LocalDate.now(),due=today.plusDays(7);
                try(PreparedStatement p=c.prepareStatement("INSERT INTO borrowings(isbn,username,borrow_date,due_date) VALUES(?,?,?,?)")){
                    p.setString(1,isbn);p.setString(2,username);p.setDate(3,java.sql.Date.valueOf(today));p.setDate(4,java.sql.Date.valueOf(due));p.executeUpdate();
                } c.commit();return due;
            }catch(Exception e){c.rollback();if(e instanceof SQLException sql)throw sql;if(e instanceof RuntimeException re)throw re;throw new SQLException(e);}
        }
    }
    private static BigDecimal returnBook(String isbn,String username) throws SQLException {
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                int id;LocalDate due;
                try(PreparedStatement p=c.prepareStatement("SELECT id,due_date FROM borrowings WHERE isbn=? AND username=? AND returned_date IS NULL FOR UPDATE")){
                    p.setString(1,isbn);p.setString(2,username);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw new IllegalArgumentException("No active loan found for this book.");id=rs.getInt(1);due=rs.getDate(2).toLocalDate();}
                }
                LocalDate today=LocalDate.now();try(PreparedStatement p=c.prepareStatement("UPDATE borrowings SET returned_date=? WHERE id=?")){p.setDate(1,java.sql.Date.valueOf(today));p.setInt(2,id);p.executeUpdate();}
                c.commit();long late=Math.max(0,ChronoUnit.DAYS.between(due,today));return new BigDecimal("0.50").multiply(BigDecimal.valueOf(late)).setScale(2);
            }catch(Exception e){c.rollback();if(e instanceof SQLException sql)throw sql;if(e instanceof RuntimeException re)throw re;throw new SQLException(e);}
        }
    }

    private static String loginPage(Flash flash) {
        return page("Library System Login", "<main class='container login-container'><h1>📚 Library Management System</h1><h2>Login / Register</h2>"+flash(flash)+
                "<form method='post' action='/auth'><input type='hidden' name='action' id='action_field' value='login'><label>Username:</label><input type='text' name='username' required maxlength='50'><label>Password:</label><input type='password' name='password' required maxlength='100'><button onclick=\"document.getElementById('action_field').value='login'\">Login</button><button class='secondary' onclick=\"document.getElementById('action_field').value='register'\">Register as Student</button></form><p class='hint'>Default librarian: librarian / admin123</p></main>");
    }
    private static String dashboardPage(Session user,Flash flash) throws SQLException {
        List<Book> books=books();StringBuilder html=new StringBuilder("<main class='container'><header class='page-header'><h1>")
                .append(user.role().equals("librarian")?"Librarian":"Student").append(" Dashboard</h1><div>Welcome, <strong>").append(esc(user.username())).append("</strong> <a class='btn logout' href='/logout'>Log out</a></div></header>")
                .append(flash(flash));
        if(user.role().equals("librarian")){
            html.append("<div class='action-group'><section class='action-card'><h2>➕ Add New Book</h2><form method='post' action='/add_book'><label>Title</label><input name='title' required maxlength='255'><label>Author</label><input name='author' required maxlength='255'><label>ISBN</label><input name='isbn' required maxlength='20'><button>Add Book</button></form></section><section class='action-card'><h2>➖ Remove Book</h2><form method='post' action='/remove_book'><label>ISBN</label><input name='isbn' required maxlength='20'><button class='danger'>Remove Book</button></form></section></div><h2>📕 Book Catalog ("+books.size()+" total)</h2>");
            html.append(bookTable(books));
        }else{
            List<Loan> loans=loans(user.username());
            html.append("<section class='message info summary'><p><strong>Total Outstanding Fines:</strong> <span class='fine-status'>$"+fines(user.username())+"</span></p><p><strong>Books Currently Borrowed:</strong> "+loans.size()+"</p></section><div class='action-group'><section class='action-card'><h2>➡️ Borrow Book</h2><form method='post' action='/borrow'><label>ISBN</label><input name='isbn' required maxlength='20'><button>Borrow</button></form></section><section class='action-card'><h2>↩️ Return Book</h2><form method='post' action='/return'><label>ISBN</label><input name='isbn' required maxlength='20'><button class='warning'>Return</button></form></section></div><h2>Your Active Loans</h2><div class='table-wrap'><table><thead><tr><th>Title</th><th>ISBN</th><th>Borrowed</th><th>Due</th></tr></thead><tbody>");
            if(loans.isEmpty())html.append("<tr><td class='empty' colspan='4'>You have no active loans.</td></tr>");
            for(Loan l:loans)html.append("<tr><td>").append(esc(l.title())).append("</td><td>").append(esc(l.isbn())).append("</td><td>").append(l.borrowed()).append("</td><td>").append(l.due()).append("</td></tr>");
            html.append("</tbody></table></div><h2>📕 Search &amp; View Catalog</h2>").append(bookTable(books));
        }
        return page(user.role().equals("librarian")?"Librarian Dashboard":"Student Dashboard",html.append("</main>").toString());
    }
    private static List<Book> books() throws SQLException {
        List<Book> out=new ArrayList<>();String sql="SELECT b.isbn,b.title,b.author,CASE WHEN EXISTS(SELECT 1 FROM borrowings x WHERE x.isbn=b.isbn AND x.returned_date IS NULL) THEN 'Borrowed' ELSE 'Available' END status FROM books b ORDER BY b.title";
        try(Connection c=connect();Statement s=c.createStatement();ResultSet rs=s.executeQuery(sql)){while(rs.next())out.add(new Book(rs.getString("isbn"),rs.getString("title"),rs.getString("author"),rs.getString("status")));}return out;
    }
    private static List<Loan> loans(String username) throws SQLException {
        List<Loan> out=new ArrayList<>();String sql="SELECT b.isbn,b.title,br.borrow_date,br.due_date FROM borrowings br JOIN books b ON b.isbn=br.isbn WHERE br.username=? AND br.returned_date IS NULL ORDER BY br.due_date";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,username);try(ResultSet rs=p.executeQuery()){while(rs.next())out.add(new Loan(rs.getString("isbn"),rs.getString("title"),rs.getDate("borrow_date").toLocalDate(),rs.getDate("due_date").toLocalDate()));}}return out;
    }
    private static String bookTable(List<Book> books){
        StringBuilder h=new StringBuilder("<div class='table-wrap'><table><thead><tr><th>Title</th><th>Author</th><th>ISBN</th><th>Status</th></tr></thead><tbody>");
        if(books.isEmpty())h.append("<tr><td class='empty' colspan='4'>No books in the catalog yet.</td></tr>");
        for(Book b:books)h.append("<tr><td>").append(esc(b.title())).append("</td><td>").append(esc(b.author())).append("</td><td>").append(esc(b.isbn())).append("</td><td class='status-").append(b.status().toLowerCase(Locale.ROOT)).append("'>").append(b.status()).append("</td></tr>");
        return h.append("</tbody></table></div>").toString();
    }
    private static String flash(Flash flash){return flash==null?"":"<div class='message "+(flash.type().equals("success")?"success":"error")+"'>"+esc(flash.text())+"</div>";}
    private static String errorPage(String message){return page("Library Error","<main class='container'><h1>Library Management System</h1><div class='message error'>"+esc(message)+"</div><a class='btn' href='/'>Back</a></main>");}
    private static String page(String title,String body){return "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>"+esc(title)+"</title><link rel='stylesheet' href='/style.css'></head><body>"+body+"</body></html>";}
    private static String esc(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
