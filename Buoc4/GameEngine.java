package vn.edu.ptit.oop.nhom05.service;

import vn.edu.ptit.oop.nhom05.model.BonusFood;
import vn.edu.ptit.oop.nhom05.model.Direction;
import vn.edu.ptit.oop.nhom05.model.Food;
import vn.edu.ptit.oop.nhom05.model.GameConfig;
import vn.edu.ptit.oop.nhom05.model.GameState;
import vn.edu.ptit.oop.nhom05.model.LevelConfig;
import vn.edu.ptit.oop.nhom05.model.Levels;
import vn.edu.ptit.oop.nhom05.model.Obstacle;
import vn.edu.ptit.oop.nhom05.model.Player;
import vn.edu.ptit.oop.nhom05.model.PoisonFood;
import vn.edu.ptit.oop.nhom05.model.Snake;

import javax.swing.Timer;
import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

public class GameEngine implements GameControls {

    private static final Color P2_BODY_COLOR = new Color(52, 132, 219);
    private static final Color P2_HEAD_COLOR = new Color(20, 70, 150);

    private final Random random;
    private final Timer timer;
    private final List<GameListener> listeners = new ArrayList<>();

    private final List<Player> players = new ArrayList<>();
    private final List<Food> foods = new ArrayList<>();
    private final List<Obstacle> obstacles = new ArrayList<>();
    private Food mainFood;
    private BonusFood bonusFood;
    private PoisonFood poisonFood;

    private GameState state = GameState.READY;
    private LevelConfig level = Levels.first();
    private int foodsEaten;
    private boolean twoPlayerMode;

    public GameEngine() {
        this(new Random());
    }

    public GameEngine(Random random) {
        this.random = Objects.requireNonNull(random, "random không được null");
        this.timer = new Timer(GameConfig.INITIAL_DELAY_MS, e -> tick());
        this.timer.setInitialDelay(GameConfig.INITIAL_DELAY_MS);
        reset();
    }

    @Override
    public void changeDirection(int playerIndex, Direction d) {
        if (!state.isRunning()) {
            return;
        }
        Player target = playerIndex > 0 && playerIndex < players.size() ? players.get(playerIndex) : players.get(0);
        target.getSnake().changeDirection(d);
    }

    @Override
    public void togglePause() {
        if (changeState(state.togglePause())) {
            notifyUpdate();
        }
    }

    @Override
    public void startOrRestart() {
        if (state.isGameOver()) {
            reset();
        }
        if (changeState(GameState.RUNNING)) {
            notifyUpdate();
        }
    }

    public void reset() {
        if (state != GameState.READY) {
            changeState(GameState.READY);
        }
        timer.stop();
        players.clear();
        players.add(new Player(1, "Người chơi 1", createSnakeOne()));
        if (twoPlayerMode) {
            players.add(new Player(2, "Người chơi 2", createSnakeTwo()));
        }
        foods.clear();
        mainFood = null;
        bonusFood = null;
        poisonFood = null;
        foodsEaten = 0;
        timer.setDelay(delayFor(0));
        spawnMainFood();
        notifyUpdate();
    }

    public void setTwoPlayerMode(boolean enabled) {
        if (state.isRunning()) {
            throw new IllegalStateException("Hãy tạm dừng trước khi đổi chế độ chơi");
        }
        if (enabled) {
            checkObstacles(obstacles, true);
        }
        twoPlayerMode = enabled;
        reset();
    }

    public void setObstacles(List<Obstacle> newObstacles) {
        Objects.requireNonNull(newObstacles, "obstacles không được null");
        if (state.isRunning() || state == GameState.PAUSED) {
            throw new IllegalStateException("Không đổi vật cản khi đang chơi");
        }
        checkObstacles(newObstacles, twoPlayerMode);
        applyObstacles(newObstacles);
    }

    public void setLevel(LevelConfig newLevel) {
        Objects.requireNonNull(newLevel, "level không được null");
        if (state.isRunning()) {
            throw new IllegalStateException("Hãy tạm dừng trước khi đổi màn");
        }
        List<Obstacle> fresh = newLevel.createObstacles();
        Objects.requireNonNull(fresh, "createObstacles() không được trả về null");
        checkObstacles(fresh, twoPlayerMode);
        level = newLevel;
        applyObstacles(fresh);
    }

    private void checkObstacles(List<Obstacle> candidates, boolean withPlayerTwo) {
        Snake probeOne = createSnakeOne();
        Snake probeTwo = withPlayerTwo ? createSnakeTwo() : null;
        for (Obstacle o : candidates) {
            Point p = Objects.requireNonNull(o, "obstacle không được null").getPosition();
            if (isOutOfBoard(p)) {
                throw new IllegalArgumentException("Vật cản nằm ngoài bàn chơi: " + p.x + "," + p.y);
            }
            if (probeOne.occupies(p) || (probeTwo != null && probeTwo.occupies(p))) {
                throw new IllegalArgumentException("Vật cản đè lên vị trí xuất phát của rắn: " + p.x + "," + p.y);
            }
        }
    }

    private void applyObstacles(List<Obstacle> validated) {
        obstacles.clear();
        obstacles.addAll(validated);
        reset();
    }

    public void addGameListener(GameListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener không được null"));
    }

    void tick() {
        if (!state.isRunning()) {
            return;
        }
        int elapsed = timer.getDelay();
        for (Food f : foods) {
            f.update(elapsed);
        }
        foods.removeIf(Food::isExpired);
        if (bonusFood != null && !foods.contains(bonusFood)) {
            bonusFood = null;
        }
        if (poisonFood != null && !foods.contains(poisonFood)) {
            poisonFood = null;
        }

        for (Player p : players) {
            p.getSnake().move();
            if (level.wrapsAround()) {
                p.getSnake().wrapHead(GameConfig.COLS, GameConfig.ROWS);
            }
        }

        boolean crashed = false;
        for (Player p : players) {
            if (hasCollision(p)) {
                p.die();
                crashed = true;
            }
        }
        if (crashed) {
            gameOver();
            return;
        }

        for (Player p : players) {
            if (!eatFoodAtHead(p)) {
                return;
            }
        }
        notifyUpdate();
    }

    private boolean hasCollision(Player player) {
        Snake snake = player.getSnake();
        Point head = snake.getHead();
        if (isOutOfBoard(head) || snake.hasHitItself()) {
            return true;
        }
        for (Obstacle o : obstacles) {
            if (o.occupies(head)) {
                return true;
            }
        }
        for (Player other : players) {
            if (other != player && other.getSnake().occupies(head)) {
                return true;
            }
        }
        return false;
    }

    private boolean eatFoodAtHead(Player player) {
        Point head = player.getSnake().getHead();
        Food eaten = null;
        for (Food f : foods) {
            if (f.occupies(head)) {
                eaten = f;
                break;
            }
        }
        if (eaten == null) {
            return true;
        }
        player.addScore(eaten.getPoints());
        eaten.applyTo(player.getSnake());
        foods.remove(eaten);
        notifyFoodEaten(eaten);

        if (eaten == poisonFood) {
            poisonFood = null;
            return true;
        }
        foodsEaten++;
        timer.setDelay(delayFor(foodsEaten));

        if (eaten == mainFood) {
            if (!spawnMainFood()) {
                gameOver();
                return false;
            }
            if (foodsEaten % GameConfig.BONUS_EVERY == 0 && bonusFood == null) {
                spawnBonusFood();
            }
            if (foodsEaten % GameConfig.POISON_EVERY == 0 && poisonFood == null) {
                spawnPoisonFood();
            }
        } else {
            bonusFood = null;
        }
        return true;
    }

    private void gameOver() {
        if (changeState(GameState.GAME_OVER)) {
            for (GameListener l : new ArrayList<>(listeners)) {
                l.onGameOver(this);
            }
        }
        notifyUpdate();
    }

    private boolean changeState(GameState next) {
        if (!state.canTransitionTo(next)) {
            return false;
        }
        state = next;
        if (state.isRunning()) {
            timer.start();
        } else {
            timer.stop();
        }
        return true;
    }

    private void notifyUpdate() {
        for (GameListener l : new ArrayList<>(listeners)) {
            l.onUpdate(this);
        }
    }

    private void notifyFoodEaten(Food food) {
        for (GameListener l : new ArrayList<>(listeners)) {
            l.onFoodEaten(this, food);
        }
    }

    static int delayFor(int foodsEaten) {
        int delay = GameConfig.INITIAL_DELAY_MS - foodsEaten * GameConfig.SPEED_STEP_MS;
        return Math.max(GameConfig.MIN_DELAY_MS, delay);
    }

    private Snake createSnakeOne() {
        return new Snake(new Point(GameConfig.COLS / 4, GameConfig.ROWS / 2), Snake.DEFAULT_LENGTH, Direction.RIGHT);
    }

    private Snake createSnakeTwo() {
        return new Snake(new Point(GameConfig.COLS - 1 - GameConfig.COLS / 4, GameConfig.ROWS / 2 - 1),
                Snake.DEFAULT_LENGTH, Direction.LEFT, P2_BODY_COLOR, P2_HEAD_COLOR);
    }

    private boolean spawnMainFood() {
        Point cell = randomFreeCell();
        if (cell == null) {
            return false;
        }
        placeMainFood(cell);
        return true;
    }

    private void spawnBonusFood() {
        Point cell = randomFreeCell();
        if (cell != null) {
            placeBonusFood(cell);
        }
    }

    private void spawnPoisonFood() {
        Point cell = randomFreeCell();
        if (cell != null) {
            placePoisonFood(cell);
        }
    }

    void placeMainFood(Point cell) {
        if (mainFood != null) {
            foods.remove(mainFood);
        }
        mainFood = new Food(cell);
        foods.add(mainFood);
    }

    void placeBonusFood(Point cell) {
        if (bonusFood != null) {
            foods.remove(bonusFood);
        }
        bonusFood = new BonusFood(cell);
        foods.add(bonusFood);
    }

    void placePoisonFood(Point cell) {
        if (poisonFood != null) {
            foods.remove(poisonFood);
        }
        poisonFood = new PoisonFood(cell);
        foods.add(poisonFood);
    }

    private Point randomFreeCell() {
        List<Point> free = new ArrayList<>();
        for (int y = 0; y < GameConfig.ROWS; y++) {
            for (int x = 0; x < GameConfig.COLS; x++) {
                Point p = new Point(x, y);
                if (isFree(p)) {
                    free.add(p);
                }
            }
        }
        return free.isEmpty() ? null : free.get(random.nextInt(free.size()));
    }

    private boolean isFree(Point cell) {
        for (Player p : players) {
            if (p.getSnake().occupies(cell)) {
                return false;
            }
        }
        for (Food f : foods) {
            if (f.occupies(cell)) {
                return false;
            }
        }
        for (Obstacle o : obstacles) {
            if (o.occupies(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOutOfBoard(Point p) {
        return p.x < 0 || p.y < 0 || p.x >= GameConfig.COLS || p.y >= GameConfig.ROWS;
    }

    public Optional<Player> getWinner() {
        if (!twoPlayerMode || !state.isGameOver()) {
            return Optional.empty();
        }
        List<Player> alive = players.stream().filter(Player::isAlive).toList();
        if (alive.size() == 1) {
            return Optional.of(alive.get(0));
        }
        Player one = players.get(0);
        Player two = players.get(1);
        if (one.getScore() == two.getScore()) {
            return Optional.empty();
        }
        return Optional.of(one.getScore() > two.getScore() ? one : two);
    }

    public Snake getSnake() { return players.get(0).getSnake(); }

    public List<Player> getPlayers() { return Collections.unmodifiableList(players); }

    public boolean isTwoPlayerMode() { return twoPlayerMode; }

    public List<Food> getFoods() { return Collections.unmodifiableList(foods); }

    public List<Obstacle> getObstacles() { return Collections.unmodifiableList(obstacles); }

    public GameState getState() { return state; }
    public int getScore() { return players.get(0).getScore(); }
    public int getLevel() { return level.getNumber(); }

    public LevelConfig getLevelConfig() { return level; }
    public int getFoodsEaten() { return foodsEaten; }

    public int getTickDelayMs() { return timer.getDelay(); }
}
