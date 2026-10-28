package vn.edu.ptit.oop.nhom05.service;

import vn.edu.ptit.oop.nhom05.model.Food;

public interface GameListener {

    void onUpdate(GameEngine engine);

    default void onFoodEaten(GameEngine engine, Food food) {
    }

    default void onGameOver(GameEngine engine) {
    }
}
