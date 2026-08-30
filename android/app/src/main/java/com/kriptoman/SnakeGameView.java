package com.kriptoman;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

public class SnakeGameView extends View implements Runnable {
    private Paint paint;
    private ArrayList<int[]> snake;
    private int[] food;
    private int direction = 1;
    private boolean running = true;
    private boolean paused = false;
    private Handler handler = new Handler();
    private int cellSize = 40;
    private int gridWidth, gridHeight;
    private int score = 0;
    private int highScore = 0;
    private Random random = new Random();
    private SharedPreferences prefs;

    public SnakeGameView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("kriptoman", Context.MODE_PRIVATE);
        highScore = prefs.getInt("highScore", 0);
        init();
    }

    public SnakeGameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences("kriptoman", Context.MODE_PRIVATE);
        highScore = prefs.getInt("highScore", 0);
        init();
    }

    public SnakeGameView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        prefs = context.getSharedPreferences("kriptoman", Context.MODE_PRIVATE);
        highScore = prefs.getInt("highScore", 0);
        init();
    }

    private void init() {
        paint = new Paint();
        snake = new ArrayList<>();
        snake.add(new int[]{5,5});
        snake.add(new int[]{4,5});
        snake.add(new int[]{3,5});
        direction = 1;
        score = 0;
        running = true;
        paused = false;
        spawnFood();
        handler.postDelayed(this, 300);
    }

    private void spawnFood() {
        gridWidth = getWidth() / cellSize;
        gridHeight = getHeight() / cellSize;
        if (gridWidth < 2) gridWidth = 10;
        if (gridHeight < 2) gridHeight = 10;
        int x,y;
        do {
            x = random.nextInt(gridWidth);
            y = random.nextInt(gridHeight);
        } while (isOnSnake(x,y));
        food = new int[]{x,y};
    }

    private boolean isOnSnake(int x, int y) {
        for (int[] p : snake) if (p[0]==x && p[1]==y) return true;
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.DKGRAY);
        paint.setColor(Color.GREEN);
        for (int[] p : snake) {
            canvas.drawRect(p[0]*cellSize, p[1]*cellSize, (p[0]+1)*cellSize, (p[1]+1)*cellSize, paint);
        }
        paint.setColor(Color.RED);
        if (food != null) {
            canvas.drawCircle(food[0]*cellSize + cellSize/2, food[1]*cellSize + cellSize/2, cellSize/2, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setTextSize(40);
        canvas.drawText("Счёт: "+score+"  Рекорд: "+highScore, 10, 60, paint);
        if (paused) {
            paint.setTextSize(80);
            canvas.drawText("ПАУЗА", getWidth()/2-100, getHeight()/2, paint);
        }
    }

    @Override
    public void run() {
        if (!running || paused) {
            handler.postDelayed(this, 200);
            return;
        }
        moveSnake();
        postInvalidate();
        handler.postDelayed(this, 250);
    }

    private void moveSnake() {
        int[] head = snake.get(0);
        int newX = head[0], newY = head[1];
        switch (direction) {
            case 0: newY--; break;
            case 1: newX++; break;
            case 2: newY++; break;
            case 3: newX--; break;
        }
        if (newX<0 || newX>=gridWidth || newY<0 || newY>=gridHeight) { running=false; return; }
        for (int i=0; i<snake.size(); i++) {
            if (snake.get(i)[0]==newX && snake.get(i)[1]==newY) { running=false; return; }
        }
        int[] newHead = new int[]{newX,newY};
        if (food != null && newX==food[0] && newY==food[1]) {
            snake.add(0, newHead);
            score++;
            if (score > highScore) {
                highScore = score;
                prefs.edit().putInt("highScore", highScore).apply();
            }
            spawnFood();
        } else {
            snake.add(0, newHead);
            snake.remove(snake.size()-1);
        }
    }

    public void togglePause() {
        paused = !paused;
        if (!paused && !running) init();
        postInvalidate();
    }

    public void resetGame() {
        init();
        postInvalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float cx = getWidth()/2f;
        float cy = getHeight()/2f;
        if (x < cx-100 && y > cy-100 && y < cy+100) direction=3;
        else if (x > cx+100 && y > cy-100 && y < cy+100) direction=1;
        else if (y < cy-100 && x > cx-100 && x < cx+100) direction=0;
        else if (y > cy+100 && x > cx-100 && x < cx+100) direction=2;
        return true;
    }
}
