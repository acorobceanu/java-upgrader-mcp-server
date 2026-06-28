package com.javaupgrader.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JavaUpgraderAgentTest {

    @TempDir
    Path tempDir;

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;
    private JavaUpgraderAgent agent;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("Upgrade complete. Changed pom.xml to Java 21.");

        agent = new JavaUpgraderAgent(chatClient);
    }

    // ---------------------------------------------------------------------------
    // run() behaviour
    // ---------------------------------------------------------------------------

    @Test
    void run_returnsAgentResponseContent() {
        String result = agent.run(tempDir.toString(), 21);
        assertEquals("Upgrade complete. Changed pom.xml to Java 21.", result);
    }

    @Test
    void run_setsProjectRootToAbsolutePath() {
        agent.run(tempDir.toString(), 21);
        assertTrue(agent.projectRoot.startsWith("/"), "projectRoot must be absolute, got: " + agent.projectRoot);
        assertFalse(agent.projectRoot.endsWith("/"), "projectRoot must not have trailing slash");
    }

    @Test
    void run_passesToolsThisToPrompt() {
        agent.run(tempDir.toString(), 21);
        // Verify the agent passed itself as the tools object so @Tool methods are registered
        verify(requestSpec).tools(agent);
    }

    @Test
    void run_handlesNullContentGracefully() {
        when(callResponseSpec.content()).thenReturn(null);
        String result = agent.run(tempDir.toString(), 21);
        assertEquals("", result, "null content should be returned as empty string");
    }

    @Test
    void run_stripsLeadingAndTrailingWhitespace() {
        when(callResponseSpec.content()).thenReturn("  summary text  \n");
        String result = agent.run(tempDir.toString(), 21);
        assertEquals("summary text", result);
    }

    // ---------------------------------------------------------------------------
    // readFile tool
    // ---------------------------------------------------------------------------

    @Test
    void readFile_returnsFileContent() throws Exception {
        Path file = tempDir.resolve("Hello.java");
        Files.writeString(file, "class Hello {}");
        agent.projectRoot = tempDir.toString();

        String result = agent.readFile("Hello.java");
        assertEquals("class Hello {}", result);
    }

    @Test
    void readFile_nonExistentFile_returnsErrorMessage() {
        agent.projectRoot = tempDir.toString();
        String result = agent.readFile("missing.java");
        assertTrue(result.startsWith("File not found:"), "Expected error prefix, got: " + result);
    }

    @Test
    void readFile_directory_returnsErrorMessage() {
        agent.projectRoot = tempDir.toString();
        String result = agent.readFile(".");
        assertTrue(result.startsWith("Path is a directory:"), "Got: " + result);
    }

    @Test
    void readFile_absolutePath_works() throws Exception {
        Path file = tempDir.resolve("abs.txt");
        Files.writeString(file, "absolute");
        agent.projectRoot = tempDir.toString();

        String result = agent.readFile(file.toAbsolutePath().toString());
        assertEquals("absolute", result);
    }

    // ---------------------------------------------------------------------------
    // writeFile tool
    // ---------------------------------------------------------------------------

    @Test
    void writeFile_createsFileWithContent() throws Exception {
        agent.projectRoot = tempDir.toString();
        String result = agent.writeFile("output.txt", "new content");
        assertTrue(result.startsWith("Wrote"), "Got: " + result);
        assertEquals("new content", Files.readString(tempDir.resolve("output.txt")));
    }

    @Test
    void writeFile_createsParentDirectories() throws Exception {
        agent.projectRoot = tempDir.toString();
        agent.writeFile("src/main/Foo.java", "class Foo {}");
        assertTrue(Files.exists(tempDir.resolve("src/main/Foo.java")));
    }

    @Test
    void writeFile_outsideProject_returnsError() {
        agent.projectRoot = tempDir.toString();
        String result = agent.writeFile("/etc/passwd", "hacked");
        assertTrue(result.startsWith("Error: refusing to write outside"),
            "Expected path-safety error, got: " + result);
        assertFalse(Files.exists(Path.of("/etc/passwd-hacked-by-test")));
    }

    // ---------------------------------------------------------------------------
    // listFiles tool
    // ---------------------------------------------------------------------------

    @Test
    void listFiles_returnsMatchingFiles() throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "class A {}");
        Files.writeString(tempDir.resolve("B.java"), "class B {}");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        agent.projectRoot = tempDir.toString();

        String result = agent.listFiles(".", "*.java");
        assertTrue(result.contains("A.java"), "Got: " + result);
        assertTrue(result.contains("B.java"), "Got: " + result);
        assertFalse(result.contains("pom.xml"), "XML should not be in result: " + result);
    }

    @Test
    void listFiles_noPattern_listsAll() throws Exception {
        // The default pattern is **/* which requires at least one path separator — so files
        // must be in a subdirectory to be matched. Files at the root of the walked directory
        // (relative path = just the filename with no '/') are not matched by **/*.
        Path sub = Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(sub.resolve("a.txt"), "");
        Files.writeString(sub.resolve("b.xml"), "");
        agent.projectRoot = tempDir.toString();

        String result = agent.listFiles(".", null);
        assertTrue(result.contains("a.txt"), "Got: " + result);
        assertTrue(result.contains("b.xml"), "Got: " + result);
    }

    @Test
    void listFiles_nonExistentDirectory_returnsError() {
        agent.projectRoot = tempDir.toString();
        String result = agent.listFiles("nonexistent-dir", null);
        assertTrue(result.startsWith("Not a directory:"), "Got: " + result);
    }

    @Test
    void listFiles_emptyDirectory_returnsNoFiles() throws Exception {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectory(emptyDir);
        agent.projectRoot = tempDir.toString();

        String result = agent.listFiles("empty", "*.java");
        assertEquals("No files found", result);
    }

    // ---------------------------------------------------------------------------
    // isWithinProject safety check
    // ---------------------------------------------------------------------------

    @Test
    void isWithinProject_fileInsideProject_returnsTrue() {
        agent.projectRoot = tempDir.toString();
        Path inside = tempDir.resolve("src/Main.java");
        assertTrue(agent.isWithinProject(inside));
    }

    @Test
    void isWithinProject_fileOutsideProject_returnsFalse() {
        agent.projectRoot = tempDir.toString();
        assertTrue(!agent.isWithinProject(Path.of("/etc/passwd")));
    }
}
