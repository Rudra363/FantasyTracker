import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Will calculate some basic week stats. Only works if ran on sunday due to some things I have
 * yet to fix. If this does not work for you message me. You gotta change some running things
 * since the league is set to private.
 */
public class EspnFantasySummary {
  private static final String GAME_KEY = "fba";
  private static final int SEASON = 2026;
  private static final long LEAGUE_ID = 313165618L;

  private static final boolean PRIVATE_LEAGUE = true;
  private static final int MAX_STARTERS = 10;
  private static final int ROLLING_DAYS = 7;

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final double EPS = 1e-9;

  private static class TeamAgg {
    String name;
    int gamesPlayed = 0;
    double weekPoints = 0.0;
  }

  public static void main(String[] args) throws Exception {
    JsonNode meta = fetchJson(String.format(
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/%s/seasons/%d/segments/0/leagues/%d?view=mTeam",
        GAME_KEY, SEASON, LEAGUE_ID
    ));

    int currentSp = meta.path("scoringPeriodId").asInt(-1);
    int currentMp = meta.path("status").path("currentMatchupPeriod").asInt(-1);
    int firstSp = meta.path("status").path("firstScoringPeriod").asInt(1);

    if (currentSp <= 0 || currentMp <= 0) {
      throw new IllegalStateException(
          "Could not determine current scoringPeriodId / currentMatchupPeriod.");
    }

    int startSp = Math.max(firstSp, currentSp - (ROLLING_DAYS - 1));
    int endSp = currentSp;

    JsonNode scoreboard = fetchJson(String.format(
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/%s/seasons/%d/segments/0/leagues/%d" +
            "?view=mTeam&view=mMatchupScore&view=mScoreboard&view=mLiveScoring" +
            "&scoringPeriodId=%d&matchupPeriodId=%d",
        GAME_KEY, SEASON, LEAGUE_ID, currentSp, currentMp
    ));

    Map<Long, TeamAgg> teams = new HashMap<>();
    for (JsonNode team : scoreboard.path("teams")) {
      long id = team.path("id").asLong();
      TeamAgg agg = teams.computeIfAbsent(id, k -> new TeamAgg());
      agg.name = team.path("name").asText("Team " + id);
    }

    for (JsonNode matchup : scoreboard.path("schedule")) {
      if (matchup.path("matchupPeriodId").asInt(-1) != currentMp) {
        continue;
      }

      JsonNode home = matchup.path("home");
      JsonNode away = matchup.path("away");

      long homeId = home.path("teamId").asLong(-1);
      long awayId = away.path("teamId").asLong(-1);

      if (homeId != -1 && teams.containsKey(homeId)) {
        teams.get(homeId).weekPoints = pickPoints(home);
      }
      if (awayId != -1 && teams.containsKey(awayId)) {
        teams.get(awayId).weekPoints = pickPoints(away);
      }
    }

    for (int sp = startSp; sp <= endSp; sp++) {
      JsonNode dayRoot = fetchJson(String.format(
          "https://lm-api-reads.fantasy.espn.com/apis/v3/games/%s/seasons/%d/segments/0/leagues/%d" +
              "?view=mTeam&view=mRoster&view=mLiveScoring&scoringPeriodId=%d",
          GAME_KEY, SEASON, LEAGUE_ID, sp
      ));

      for (JsonNode team : dayRoot.path("teams")) {
        long teamId = team.path("id").asLong();
        TeamAgg agg = teams.get(teamId);
        if (agg == null) {
          continue;
        }

        List<JsonNode> starters = collectStarters(team);

        for (JsonNode entry : starters) {
          JsonNode player = entry.path("playerPoolEntry").path("player");
          if (didPlayThisDay(player, sp)) {
            agg.gamesPlayed++;
          }
        }
      }
    }

    System.out.println();
    System.out.println("Current Week" + "(Machup " + currentMp + ")");
    System.out.println();
    System.out.printf("%-24s %14s %12s %12s%n",
        "Team", "Games (Last 7)", "Week Points", "Avg/Game");
    System.out.println(
        "--------------------------------------------------------------------------");

    List<Long> ids = new ArrayList<>(teams.keySet());
    ids.sort(Comparator.naturalOrder());

    for (long id : ids) {
      TeamAgg t = teams.get(id);
      double avg = (t.gamesPlayed == 0) ? 0.0 : (t.weekPoints / t.gamesPlayed);
      System.out.printf("%-24s %14d %12.2f %12.2f%n",
          trimTo(t.name, 24), t.gamesPlayed, t.weekPoints, avg);
    }
    System.out.println(
        "--------------------------------------------------------------------------");
  }

  /**
   * Starters = lineupSlotId < 20, sorted, take up to MAX_STARTERS.
   */
  private static List<JsonNode> collectStarters(JsonNode team) {
    List<JsonNode> starters = new ArrayList<>();
    for (JsonNode entry : team.path("roster").path("entries")) {
      int slotId = entry.path("lineupSlotId").asInt(-1);
      if (slotId >= 0 && slotId < 20) {
        starters.add(entry);
      }
    }
    starters.sort(Comparator.comparingInt(e -> e.path("lineupSlotId").asInt(999)));
    if (starters.size() > MAX_STARTERS) {
      starters = starters.subList(0, MAX_STARTERS);
    }
    return starters;
  }

  /**
   * "Played" if actual stat line exists and at least one stat is non-zero.
   */
  private static boolean didPlayThisDay(JsonNode player, int scoringPeriodId) {
    JsonNode statsArray = player.path("stats");
    if (!statsArray.isArray()) {
      return false;
    }

    for (JsonNode statLine : statsArray) {
      int sp = statLine.path("scoringPeriodId").asInt(-1);
      int src = statLine.path("statSourceId").asInt(-1);
      if (sp != scoringPeriodId || src != 0) {
        continue;
      }

      JsonNode statsMap = statLine.path("stats");
      if (statsMap == null || !statsMap.isObject()) {
        return false;
      }

      Iterator<Map.Entry<String, JsonNode>> it = statsMap.fields();
      while (it.hasNext()) {
        JsonNode v = it.next().getValue();
        if (v.isNumber() && Math.abs(v.asDouble()) > EPS) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  private static double pickPoints(JsonNode side) {
    if (side.hasNonNull("totalPointsLive")) {
      return side.path("totalPointsLive").asDouble(0.0);
    }
    if (side.hasNonNull("totalPoints")) {
      return side.path("totalPoints").asDouble(0.0);
    }
    return 0.0;
  }

  private static String padRight(String s, int n) {
    if (s.length() >= n) {
      return s;
    }
    return s + " ".repeat(n - s.length());
  }

  private static String trimTo(String s, int n) {
    if (s == null) {
      return "";
    }
    if (s.length() <= n) {
      return s;
    }
    return s.substring(0, Math.max(0, n - 1)) + "…";
  }

  private static JsonNode fetchJson(String url) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
        .header("Accept", "application/json")
        .GET();

    if (PRIVATE_LEAGUE) {
      String swid = System.getenv("ESPN_SWID");
      String s2 = System.getenv("ESPN_S2");
      if (swid == null || s2 == null) {
        throw new IllegalStateException(
            "Missing env vars ESPN_SWID and/or ESPN_S2 (private league).");
      }
      b.header("Cookie", "SWID=" + swid + "; espn_s2=" + s2);
    }

    HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new RuntimeException(
          "ESPN request failed: HTTP " + resp.statusCode() + "\n" + resp.body());
    }
    return MAPPER.readTree(resp.body());
  }
}