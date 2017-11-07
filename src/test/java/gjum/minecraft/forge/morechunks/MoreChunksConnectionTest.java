package gjum.minecraft.forge.morechunks;

import gjum.minecraft.forge.morechunks.MockChunkServer.ChunkServerCall;
import junit.framework.TestCase;

import static gjum.minecraft.forge.morechunks.IChunkServer.INFO_SET_CHUNKS_PER_SEC;

public class MoreChunksConnectionTest extends TestCase {
    private MoreChunks moreChunks;
    private MockMcGame game;
    private MockChunkServer chunkServer;
    private IConfig conf;
    private MockEnv env;

    public void setUp() throws Exception {
        super.setUp();
        game = new MockMcGame();
        chunkServer = new MockChunkServer();
        conf = new Config();
        env = new MockEnv();
        moreChunks = new MoreChunks(game, conf, env);
        moreChunks.setChunkServer(chunkServer);
    }

    public void testConnectsChunkServerOnJoinGame() {
        chunkServer.connected = false;
        game.ingame = true;
        moreChunks.onGameConnected();
        assertTrue(chunkServer.containsCall(ChunkServerCall.CONNECT));
    }

    public void testDisconnectsChunkServerOnLeaveGame() {
        chunkServer.connected = true;
        game.ingame = true; // by the nature of Forge's disconnect event, game is still connected at that time
        moreChunks.onGameDisconnected();
        assertEquals("Should disconnect chunk server when leaving game", ChunkServerCall.DISCONNECT, chunkServer.getLastCall().call);
        ExpectedDisconnect reason = new ExpectedDisconnect("MoreChunks: Game ending");
        assertEquals(reason, chunkServer.getLastCall().args[0]);

        chunkServer.connected = false;
        moreChunks.onChunkServerDisconnected(reason);
        assertTrue("Should not reconnect chunk server when leaving game",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));
    }

    public void testReconnectsOnChunkServerDisconnectWhenIngame() {
        chunkServer.connected = false;
        game.ingame = true;
        moreChunks.onChunkServerDisconnected(new DisconnectReason("Test"));
        assertEquals(ChunkServerCall.CONNECT, chunkServer.getLastCall().call);
    }

    public void testNoReconnectionOnChunkServerDisconnectWhenNotIngame() {
        chunkServer.connected = false;
        game.ingame = false;
        moreChunks.onChunkServerDisconnected(new DisconnectReason("Test"));
        assertFalse(chunkServer.containsCall(ChunkServerCall.CONNECT));
    }

    public void testDisconnectsChunkServerOnConnectWhenNotIngame() {
        chunkServer.connected = true;
        game.ingame = false;
        moreChunks.onChunkServerConnected();
        assertEquals(ChunkServerCall.DISCONNECT, chunkServer.getLastCall().call);
        ExpectedDisconnect reason = new ExpectedDisconnect("MoreChunks: No game running");
        assertEquals(reason, chunkServer.getLastCall().args[0]);
    }

    public void testNoReconnectOnConnectChunkServerWhenNotIngame() {
        chunkServer.connected = true;
        game.ingame = true;
        moreChunks.onChunkServerConnected();
        assertTrue(chunkServer.calls.isEmpty());
    }

    public void testReconnectWithExponentialBackoff() {
        game.ingame = true;
        chunkServer.connected = false;

        env.nowMs = 0;
        moreChunks.onChunkServerDisconnected(new DisconnectReason("Test"));
        assertTrue("first reconnect attempt should happen instantly",
                chunkServer.containsCall(ChunkServerCall.CONNECT));

        chunkServer.calls.clear();
        env.nowMs = 1;
        moreChunks.onTick();
        assertTrue("should not reconnect while waiting for timeout",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));

        moreChunks.onChunkServerDisconnected(new DisconnectReason("Test"));
        assertTrue("second reconnect attempt should not happen instantly",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));

        final long firstTimeout = 1000;

        chunkServer.calls.clear();
        env.nowMs = firstTimeout - 1;
        moreChunks.onTick();
        assertTrue("second reconnect attempt should not happen before timeout",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));

        chunkServer.calls.clear();
        env.nowMs = firstTimeout;
        moreChunks.onTick();
        assertTrue("second reconnect attempt should happen after timeout",
                chunkServer.containsCall(ChunkServerCall.CONNECT));

        final long secondTimeout = firstTimeout + 2 * firstTimeout;
        chunkServer.calls.clear();
        env.nowMs = secondTimeout - 1;
        moreChunks.onTick();
        assertTrue("third reconnect attempt should not happen before double timeout",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));

        chunkServer.calls.clear();
        env.nowMs = secondTimeout;
        moreChunks.onTick();
        assertTrue("third reconnect attempt should happen after double timeout",
                chunkServer.containsCall(ChunkServerCall.CONNECT));

        chunkServer.calls.clear();
        moreChunks.onChunkServerConnected();
        assertTrue("successful connection should not result in reconnect attempt",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));

        moreChunks.onChunkServerDisconnected(new DisconnectReason("Test"));
        assertTrue("successful connection should reset reconnect timeout",
                chunkServer.containsCall(ChunkServerCall.CONNECT));

        final long firstTimeoutPart2 = secondTimeout + firstTimeout;

        chunkServer.calls.clear();
        env.nowMs = firstTimeoutPart2 - 1;
        moreChunks.onTick();
        assertTrue("second reconnect attempt should not happen before first timeout (after a successful connection had been made)",
                !chunkServer.containsCall(ChunkServerCall.CONNECT));

        chunkServer.calls.clear();
        env.nowMs = firstTimeoutPart2;
        moreChunks.onTick();
        assertTrue("successful connection should reset reconnect interval",
                chunkServer.containsCall(ChunkServerCall.CONNECT));
    }

    public void testSendsChunkSpeedWhenChangedInConfig() {
        Config newConfig = new Config();
        newConfig.setChunkLoadsPerSecond(42);

        moreChunks.onConfigChanged(newConfig);

        assertEquals("Should send new chunk speed to server when it changed in the config",
                INFO_SET_CHUNKS_PER_SEC + "42", chunkServer.getLastCall().args[0]);
    }

    public void testSendsNoChunkSpeedWhenNotChangedInConfig() {
        Config newConfig = new Config();
        newConfig.setChunkLoadsPerSecond(conf.getChunkLoadsPerSecond());

        moreChunks.onConfigChanged(newConfig);

        assertTrue("Should not send chunk speed to server when it didn't change",
                !chunkServer.containsCall(ChunkServerCall.SEND_STRING_MSG));
    }

    // TODO test when not enabled

    // TODO cap out reconnect time at 60sec
}
