# Library Management System —  Java + JDBC

This project uses  Java, direct JDBC calls to MySQL, and Java's built-in HTTP server. It does not use Spring Boot, Spring, or an external servlet container. The browser screens retain the supplied project's library workflow and stylesheet.

## Requirements

- Java 21 or later
- Maven (used only to download the MySQL JDBC driver and package the app)
- MySQL Server

## Run it

1. In MySQL Workbench, run `database_setup.sql` to create `library_db`, the tables, and the demo librarian account. The MySQL account must have permission to create the database.
2. In PowerShell, set your MySQL account details (replace the password with your own):

   ```powershell
   $env:LIBRARY_DB_USER = "root"
   $env:LIBRARY_DB_PASSWORD = "your_mysql_password"
   ```

   The default JDBC URL is `jdbc:mysql://127.0.0.1:3306/library_db?serverTimezone=UTC`. To use a different URL, set `LIBRARY_DB_URL`.

3. Open PowerShell in this project folder and start the application:

   ```powershell
   mvn package
   java -jar target/library-management-1.0.0.jar
   ```

4. Visit `http://localhost:8080`. To use a different port, set `$env:PORT` before running the jar.

## Run from Visual Studio Code

1. Install the **Extension Pack for Java** in VS Code, and make sure Java 21+ and Maven are installed.
2. Run `database_setup.sql` in MySQL Workbench and make sure the MySQL server is running.
3. Open this project folder in VS Code (`File` → `Open Folder`). Wait for the Java and Maven project import to finish.
4. Open `.vscode/launch.json` and replace `CHANGE_ME` with your MySQL password. Save the file.
5. Open `src/main/java/edu/library/App.java` and click **Run** above `main`, or choose **Run Library Management System** in the Run and Debug panel.
6. Open `http://localhost:8080` in your browser. VS Code's terminal will show the server output; stop it with the Stop button or Ctrl+C.

The launch configuration only sets `LIBRARY_DB_USER` and `LIBRARY_DB_PASSWORD`; the app uses the default JDBC URL unless you set `LIBRARY_DB_URL` too.

The demo librarian login is **librarian / admin123**. Students can register on the login page. Change the demo password before using this beyond a classroom demonstration.

## JDBC connection in the code

`App.java` reads `LIBRARY_DB_URL`, `LIBRARY_DB_USER`, and `LIBRARY_DB_PASSWORD`, then opens connections with `DriverManager.getConnection(...)`. Database operations use `PreparedStatement` and close connections and statements with try-with-resources.

## Features

- Student registration and login; librarian login
- Librarian can add books, remove books without borrowing history, and view availability
- Students can view the catalog and active loans, borrow books, and return them
- Seven-day loan period; overdue fines are $0.50 per day
- Outstanding fines prevent additional borrowing


