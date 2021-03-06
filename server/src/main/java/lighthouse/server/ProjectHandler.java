package lighthouse.server;

import com.google.common.base.*;
import com.google.common.io.*;
import com.google.protobuf.*;
import com.googlecode.protobuf.format.*;
import com.sun.net.httpserver.*;
import javafx.collections.*;
import lighthouse.*;
import lighthouse.protocol.*;
import lighthouse.threading.*;
import org.bitcoinj.core.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;

import static java.net.HttpURLConnection.*;

/**
 * Handler that manages all inbound requests.
 */
public class ProjectHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ProjectHandler.class);

    // Refuse to read >1mb of data.
    private static final long MAX_REQUEST_SIZE_BYTES = 1024 * 1024;

    private final LighthouseBackend backend;
    private final AffinityExecutor executor;
    private final ObservableMap<Sha256Hash, LighthouseBackend.ProjectStateInfo> projectStates;
    private final ObservableMap<Project, LighthouseBackend.CheckStatus> checkStates;

    private final Map<Project, ObservableSet<LHProtos.Pledge>> pledges = new HashMap<>();

    enum DownloadFormat {
        PBUF,
        JSON,
        HTML,
        XML,
        LIGHTHOUSE_PROJECT   // protobuf of the project file itself
    }

    public ProjectHandler(LighthouseBackend backend) {
        this.backend = backend;
        // This might change in future so alias it to keep assertions simple.
        this.executor = backend.getExecutor();
        this.projectStates = backend.mirrorProjectStates(executor);
        this.checkStates = backend.mirrorCheckStatuses(executor);
    }

    public void sendError(HttpExchange exchange, int code) {
        try {
            log.warn("Returning HTTP error {}", code);
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        } catch (IOException e) {
            Throwables.propagate(e);
        }
    }

    public void sendSuccess(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        } catch (IOException e) {
            Throwables.propagate(e);
        }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        // TODO: Use a SimpleTimeLimiter here to implement request timeouts.
        try {
            realHandle(httpExchange);
        } catch (Exception e) {
            log.error("Error handling request from {}: {}", httpExchange.getRemoteAddress().getAddress().getHostAddress(), httpExchange.getRequestURI());
            log.error("Took exception", e);
            sendError(httpExchange, HTTP_INTERNAL_ERROR);
        }
    }

    private void realHandle(HttpExchange httpExchange) throws Exception {
        executor.checkOnThread();
        // Simulate a slow server.
        // Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);

        final String method = httpExchange.getRequestMethod();
        URI uri = httpExchange.getRequestURI();
        String path = uri.toString();
        log.info("{}: REQ: {} {}", httpExchange.getRemoteAddress().getAddress().getHostAddress(), method, path);
        if (!path.startsWith(LHUtils.HTTP_PATH_PREFIX + LHUtils.HTTP_PROJECT_PATH)) {
            sendError(httpExchange, HTTP_NOT_FOUND);
            return;
        }
        DownloadFormat format = DownloadFormat.PBUF;
        if (method.equals("GET") && pathEndsWithFormat(path)) {
            try {
                format = DownloadFormat.valueOf(path.substring(path.lastIndexOf(".") + 1).toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                log.error("Could not figure out format from extension, defaulting to protobuf");
            }
            path = path.substring(0, path.lastIndexOf("."));
            uri = new URI(uri.getScheme(), uri.getHost(), path, uri.getRawFragment());
        }
        Project project = backend.getProjectFromURL(uri);
        if (project == null) {
            log.warn("Project URL did not match any known project", uri);
            sendError(httpExchange, HTTP_NOT_FOUND);
            return;
        }
        switch (method) {
            case "POST": pledgeUpload(httpExchange, project); break;
            case "GET": download(httpExchange, project, format); break;
            default: sendError(httpExchange, HTTP_BAD_METHOD); break;
        }
    }

    private boolean pathEndsWithFormat(String path) {
        return (path.endsWith(".json") || path.endsWith(".xml") || path.endsWith(".html") || path.endsWith(".lighthouse-project"));
    }

    private ObservableSet<LHProtos.Pledge> getPledgesFor(Project project) {
        ObservableSet<LHProtos.Pledge> result = pledges.get(project);
        if (result == null) {
            result = backend.mirrorOpenPledges(project, executor);
            pledges.put(project, result);
        }
        return result;
    }

    private void download(HttpExchange httpExchange, Project project, DownloadFormat format) throws IOException, SignatureException {
        if (format == DownloadFormat.LIGHTHOUSE_PROJECT) {
            log.info("Replying with project file");
            byte[] bits = project.getProto().toByteArray();
            httpExchange.getResponseHeaders().add("Content-Type", LHUtils.PROJECT_MIME_TYPE);
            httpExchange.sendResponseHeaders(HTTP_OK, bits.length);
            httpExchange.getResponseBody().write(bits);
            httpExchange.close();
            return;
        }

        LighthouseBackend.CheckStatus checkStatus = checkStates.get(project);
        if (checkStatus != null) {
            Throwable checkError = checkStatus.getError();
            if (checkError != null) {
                log.error("Replying with 500 due to check error", checkError);
                byte[] bits = ("Error when checking project: " + checkError).getBytes();
                httpExchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, bits.length);
                httpExchange.getResponseBody().write(bits);
                httpExchange.close();
                return;
            }
        }

        LHProtos.ProjectStatus.Builder status = LHProtos.ProjectStatus.newBuilder();
        status.setId(project.getID());
        status.setTimestamp(Instant.now().getEpochSecond());

        Map<String, String> params;
        String queryParams = httpExchange.getRequestURI().getRawQuery();
        if (queryParams != null && !queryParams.isEmpty()) {
            // Why doesn't the URI API have this? That's stupid.
            params = Splitter.on('&').trimResults().withKeyValueSeparator('=').split(queryParams);
        } else {
            params = new HashMap<>();
        }

        boolean authenticated = false;
        String signature = params.get("sig");
        String message = params.get("msg");
        if (signature != null && message != null) {
            signature = URLDecoder.decode(signature, "UTF-8");
            message = URLDecoder.decode(message, "UTF-8");
            log.info("Attempting to authenticate project owner");
            project.authenticateOwner(message, signature);   // throws SignatureException
            log.info("... authenticated OK");
            authenticated = true;
        }

        long totalPledged = addPledgesToStatus(status, authenticated, getPledgesFor(project));

        LighthouseBackend.ProjectStateInfo info = projectStates.get(project.getIDHash());
        if (info.getClaimedBy() != null) {
            status.setClaimedBy(ByteString.copyFrom(info.getClaimedBy().getBytes()));
        }

        status.setValuePledgedSoFar(totalPledged);
        final LHProtos.ProjectStatus proto = status.build();
        byte[] bits;
        switch (format) {
            case PBUF: bits = proto.toByteArray(); break;
            case JSON: bits = JsonFormat.printToString(proto).getBytes(Charsets.UTF_8); break;
            case HTML: bits = HtmlFormat.printToString(proto).getBytes(Charsets.UTF_8); break;
            case XML: bits = XmlFormat.printToString(proto).getBytes(Charsets.UTF_8); break;
            default: throw new AssertionError();
        }
        httpExchange.sendResponseHeaders(HTTP_OK, bits.length);
        httpExchange.getResponseBody().write(bits);
        httpExchange.close();
    }

    private long addPledgesToStatus(LHProtos.ProjectStatus.Builder status, boolean authenticated, Set<LHProtos.Pledge> pledges) {
        long totalPledged = 0;
        for (LHProtos.Pledge pledge : pledges) {
            if (authenticated) {
                status.addPledges(pledge);
            } else {
                // Remove transactions so the contract can't be closed by anyone who requests the status.
                // Also remove email addresses.
                //
                // In future we may wish to optionally relax this constraint so anyone who can observe the project
                // can prove to themselves the pledges really exist, and the contract can be closed by any user.
                //
                // This should all be replaced with the use of encryption. The server doesn't need to know this
                // stuff at all.
                Sha256Hash origHash = LHUtils.hashFromPledge(pledge);
                LHProtos.Pledge.Builder scrubbedPledge = pledge.toBuilder().clearTransactions();
                LHProtos.PledgeDetails.Builder details = scrubbedPledge.getPledgeDetailsBuilder();
                details.setOrigHash(ByteString.copyFrom(origHash.getBytes()));
                details.clearContactAddress();
                status.addPledges(scrubbedPledge);
            }
            totalPledged += pledge.getPledgeDetails().getTotalInputValue();
        }
        return totalPledged;
    }

    private void pledgeUpload(HttpExchange httpExchange, Project project) throws IOException {
        // HTTP POST to /_lighthouse/crowdfund/project/$ID should contain a serialized Pledge message.
        InputStream input = ByteStreams.limit(httpExchange.getRequestBody(), MAX_REQUEST_SIZE_BYTES);
        final LHProtos.Pledge pledge;
        try {
            pledge = LHProtos.Pledge.parseFrom(input);
        } catch (Exception e) {
            log.error("Failed to read pledge protobuf: {}", e);
            sendError(httpExchange, HTTP_INTERNAL_ERROR);
            return;
        }
        log.info("Pledge uploaded from {} for project '{}'", httpExchange.getRemoteAddress(), project);
        backend.submitPledge(project, pledge).whenCompleteAsync((p, ex) -> {
            if (ex != null || p == null) {
                log.error("Submitted pledge failed processing: " + pledge);
                sendError(httpExchange, HTTP_BAD_REQUEST);
            } else {
                log.info("Pledge accepted!");
                sendSuccess(httpExchange);
            }
        }, executor);
    }
}
