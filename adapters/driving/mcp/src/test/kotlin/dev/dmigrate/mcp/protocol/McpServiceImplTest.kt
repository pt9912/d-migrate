package dev.dmigrate.mcp.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.ExecutionException

class McpServiceImplTest : FunSpec({

    test("initialize with correct protocolVersion returns server metadata") {
        val sut = McpServiceImpl(serverVersion = "9.9.9")
        val result = sut.initialize(
            InitializeParams(
                protocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
                clientInfo = ClientInfo("test-client", "1.0"),
            ),
        ).get()
        result.protocolVersion shouldBe McpProtocol.MCP_PROTOCOL_VERSION
        result.serverInfo.name shouldBe McpProtocol.SERVER_NAME
        result.serverInfo.version shouldBe "9.9.9"
        sut.negotiatedProtocolVersion() shouldBe McpProtocol.MCP_PROTOCOL_VERSION
    }

    test("initialize with wrong protocolVersion fails with InvalidParams") {
        val sut = McpServiceImpl(serverVersion = "9.9.9")
        val ex = shouldThrow<ExecutionException> {
            sut.initialize(
                InitializeParams(
                    protocolVersion = "1999-01-01",
                    clientInfo = ClientInfo("test-client", "1.0"),
                ),
            ).get()
        }
        val cause = ex.cause as ResponseErrorException
        cause.responseError.code shouldBe ResponseErrorCode.InvalidParams.value
        sut.negotiatedProtocolVersion() shouldBe null
    }

    test("default capabilities advertise nothing implemented (AP 6.4 minimum)") {
        val sut = McpServiceImpl(serverVersion = "0.1.0")
        val result = sut.initialize(
            InitializeParams(protocolVersion = McpProtocol.MCP_PROTOCOL_VERSION),
        ).get()
        // §5.3: capabilities reflect only what is implemented. AP 6.8
        // adds tools, AP 6.9 adds resources.
        result.capabilities.tools shouldBe null
        result.capabilities.resources shouldBe null
        result.capabilities.prompts shouldBe null
    }

    test("initialized notification is a no-op") {
        val sut = McpServiceImpl(serverVersion = "0.1.0")
        sut.initialized() // must not throw
    }
})
