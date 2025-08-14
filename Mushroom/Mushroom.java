import dev.robocode.tankroyale.botapi.*;
import dev.robocode.tankroyale.botapi.events.*;
import dev.robocode.tankroyale.botapi.graphics.Color;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Mushroom extends Bot {
    // 记录敌方发射的子弹波合集
    public List enemyWaves;
    // 记录敌方子弹的相对运动方向
    public List surfDirections;
    // 记录敌方子弹的绝对角度
    public List surfAbsBearings;

    // 本机器人所处的位置坐标
    public Point2D.Double myLocation;
    // 敌方机器人所处的位置坐标
    public Point2D.Double enemyLocation;
    // 统计危险区域，47个区间：通过历史被命中数据，标记不同方向的危险程度
    public static int BINS = 47;
    public static double surfStats[] = new double[BINS];

    // 初始能量值
    public double oppEnergy = 100.0;
    public double myEnergy = 100.0;

    public Point2D.Double preLocation;
    public Point2D.Double curLocation;

    // 战场边界
    // 表示1200*1200战场的矩形区域
    // 用于简单的迭代式墙壁平滑方法（由Kawigi提出）
    // 如果不熟悉墙壁平滑，wall stick表示我们尝试在坦克两端保持的空间大小
    // （向前或向后延伸）以避免触碰墙壁
    public static Rectangle2D.Double fieldRect
            = new java.awt.geom.Rectangle2D.Double(20, 20, 1160, 1160);

    @Override
    public void run() {
        enemyWaves = new ArrayList();
        surfDirections = new ArrayList();
        surfAbsBearings = new ArrayList<>();

        myLocation = new Point2D.Double(getX(), getY());
        preLocation = new Point2D.Double(getX(), getY());
        curLocation = new Point2D.Double(getX(), getY());

        // 设置炮塔独立于车身旋转
        setAdjustGunForBodyTurn(true);
        // 设置雷达独立于炮塔旋转
        setAdjustRadarForGunTurn(true);

        setBotEventPriority(ScannedBotEvent.class, 120);
        setBotEventPriority(HitWallEvent.class, 110);
        setBotEventPriority(HitWallEvent.class, 100);
        setBotEventPriority(ScannedWallEvent.class, 90);
        setBotEventPriority(CustomEvent.class, 20);
        setBotEventPriority(TeamMessageEvent.class, 15);
        setBotEventPriority(BotDeathEvent.class, 30);
        setBotEventPriority(BulletHitWallEvent.class, 40);

        int t = 0;

        do {
            // 雷达无限旋转
            // turnRadarRight(Double.POSITIVE_INFINITY);
            turnRadarRight(30);

            t++;
            if (t == 30) {
                if (curLocation.getX() == preLocation.getX() && curLocation.getY() == preLocation.getY() && getEnergy() >= 30) {
                    forward(10);
                    back(10);
                }

                preLocation.setLocation(curLocation.getX(), curLocation.getY());
                curLocation.setLocation(getX(), getY());
                t = 0;
            }
        } while (isRunning());
    }

    public void setBotEventPriority(Class<? extends BotEvent> eventClass, int priority) {
        setEventPriority((Class<BotEvent>) (Class<?>) eventClass, priority);
    }

    // 敌方波浪内部类
    class EnemyWave {
        Point2D.Double fireLocation; // 开火位置
        long fireTime;               // 开火时间
        double bulletVelocity;       // 子弹速度
        double directAngle;          // 直接角度
        double distanceTraveled;     // 已传播距离
        int direction;               // 方向（1或-1）

        public EnemyWave() {
        }
    }

    // 根据子弹能量计算子弹速度
    public static double bulletVelocity(double power) {
        return (20D - (3D * power));
    }

    // ******
    // 从源位置按指定角度和距离投影新点（来自CassiusClay，作者PEZ）
    public static Point2D.Double project(Point2D.Double sourceLocation, double angle, double length) {
        return new Point2D.Double(
                sourceLocation.x + Math.cos(angle) * length,
                sourceLocation.y + Math.sin(angle) * length
        );
    }

    public void updateWaves() {
        for (int x = 0; x < enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave) enemyWaves.get(x);

            // 更新波浪已传播距离
            ew.distanceTraveled = (getTurnNumber() - ew.fireTime) * ew.bulletVelocity;
            // 如果波浪已传播超过本机位置50像素以上，则移除该波浪
            if (ew.distanceTraveled > myLocation.distance(ew.fireLocation) + 50) {
                enemyWaves.remove(x);
                x--;
            }
        }
    }

    // 获取最近的可冲浪波浪
    public EnemyWave getClosestSurfableWave() {
        double closestDistance = 50000; // 初始设为很大的数值
        EnemyWave surfWave = null;

        for (int x = 0; x < enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave) enemyWaves.get(x);
            // 计算波浪与本机的距离
            double distance = myLocation.distance(ew.fireLocation) - ew.distanceTraveled;

            // 找到最近的有效波浪
            // distance <= bulletVelocity说明子弹 已在当前 Tick 到达或越过本机位置，即无效
            if (distance > ew.bulletVelocity && distance < closestDistance) {
                surfWave = ew;
                closestDistance = distance;
            }
        }

        return surfWave;
    }

    // ******
    // 计算从源点到目标点的绝对角度（弧度）（来自RaikoMicro，作者Jamougha）
    // 相当于directionTo()
    public static double absoluteBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.y - source.y, target.x - source.x);
    }

    // 计算最大逃脱角度
    public static double maxEscapeAngle(double velocity) {
        return Math.asin(8.0 / velocity);
    }

    // 数值限制函数
    public static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    // 根据敌方波浪和被击中的位置，计算对应的危险统计数组索引
    public int getFactorIndex(EnemyWave ew, Point2D.Double targetLocation) {
        // 计算偏移角度
        double offsetAngle = absoluteBearing(ew.fireLocation, targetLocation) - ew.directAngle;
        // 标准化并计算因子
        double factor = normalizeRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

        // 限制在0到BINS-1范围内
        return (int) limit(0, (factor * ((BINS - 1) / 2)) + ((BINS - 1) / 2), BINS - 1);
    }

    private static double WALL_STICK = 160;

    // 迭代式墙壁平滑方法（由Kawigi提出）
    // 返回考虑墙壁平滑后的绝对移动角度
    public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
        // // 微调角度直到不会撞墙
        // while (!fieldRect.contains(project(botLocation, angle, 160))) {
        //     angle += orientation * 0.05;
        // }
        // return angle;

        // 1靠近敌人，-1远离敌人
        // int smoothTowardEnemy = Math.random() > 0.5 ? 1 : -1;
        int smoothTowardEnemy = 1;

        double x = botLocation.getX();
        double y = botLocation.getY();

        angle += (4 * Math.PI);

        double testX = x + (Math.sin(angle) * WALL_STICK);
        double testY = y + (Math.cos(angle) * WALL_STICK);
        double wallDistanceX = Math.min(x - 20, 1180 - x);
        double wallDistanceY = Math.min(y - 20, 1180 - y);
        double testDistanceX = Math.min(testX - 20, 1180 - testX);
        double testDistanceY = Math.min(testY - 20, 1180 - testY);

        double adjacent = 0;
        int g = 0;

        while (!fieldRect.contains(testX, testY) && g++ < 50) {
            if (testDistanceY < 0 && testDistanceY < testDistanceX) {
                // wall smooth North or South wall
                angle = ((int) ((angle + (Math.PI / 2)) / Math.PI)) * Math.PI;
                adjacent = Math.abs(wallDistanceY);
            } else if (testDistanceX < 0 && testDistanceX <= testDistanceY) {
                // wall smooth East or West wall
                angle = (((int) (angle / Math.PI)) * Math.PI) + (Math.PI / 2);
                adjacent = Math.abs(wallDistanceX);
            }

            // use your own equivalent of (1 / POSITIVE_INFINITY) instead of 0.005
            // if you want to stay closer to the wall ;)
            angle += smoothTowardEnemy * orientation *
                    (Math.abs(Math.acos(adjacent / WALL_STICK)) + 0.005);
            // angle += orientation * (Math.abs(Math.acos(adjacent / WALL_STICK)) + 0.005);

            testX = x + (Math.sin(angle) * WALL_STICK);
            testY = y + (Math.cos(angle) * WALL_STICK);
            testDistanceX = Math.min(testX - 20, 1180 - testX);
            testDistanceY = Math.min(testY - 20, 1180 - testY);

            // if (smoothTowardEnemy == -1) {
            //     // this method ended with tank smoothing away from enemy... you may
            //     // need to note that globally, or maybe you don't care.
            // }
        }

        return angle;
    }

    // 预测位置方法（来自Apollon的迷你预测器，作者rozu）
    public Point2D.Double predictPosition(EnemyWave surfWave, int direction) {
        Point2D.Double predictedPosition = (Point2D.Double) myLocation.clone();
        double predictedVelocity = getSpeed();
        double predictedHeading = getDirection();
        double maxTurning, moveAngle, moveDir;

        int counter = 0; // 未来Tick计数器
        boolean intercepted = false; // 是否被拦截标志

        do {
            // 计算移动角度（考虑墙壁平滑）
            moveAngle = wallSmoothing(
                    predictedPosition,
                    absoluteBearing(surfWave.fireLocation, predictedPosition) + (direction * (Math.PI / 2)),
                    direction
            ) - predictedHeading;
            moveDir = 1;

            if (Math.cos(moveAngle) < 0) {
                // 反向移动
                moveAngle += Math.PI;
                moveDir = -1;
            }

            moveAngle = normalizeRelativeAngle(moveAngle);

            // 最大转向角度(每Tick)
            maxTurning = Math.PI / 720d * (40d - 3d * Math.abs(predictedVelocity));
            predictedHeading = normalizeRelativeAngle(
                    predictedHeading + limit(-maxTurning, moveAngle, maxTurning)
            );

            // 速度变化规则：如果速度和方向相反则两倍减速，否则加速
            predictedVelocity += (predictedVelocity * moveDir < 0 ? 2 * moveDir : moveDir);
            // 游戏规则：速度最大为8个单位
            predictedVelocity = limit(-8, predictedVelocity, 8);

            // 计算新预测位置
            predictedPosition = project(predictedPosition, predictedHeading, predictedVelocity);

            counter++;

            // 检查是否会被子弹拦截
            if (predictedPosition.distance(surfWave.fireLocation)
                    < surfWave.distanceTraveled + (counter * surfWave.bulletVelocity) + surfWave.bulletVelocity) {
                intercepted = true;
            }
        } while (!intercepted && counter < 500);

        return predictedPosition;
    }

    // 检查指定方向的危险值
    public double checkDanger(EnemyWave surfWave, int direction) {
        int index = getFactorIndex(surfWave, predictPosition(surfWave, direction));
        return surfStats[index];
    }

    // 设置前进或后退（保持指定角度）
    public void setBackAsFront(Bot robot, double goAngle) {
        double angle = normalizeRelativeAngle(goAngle - robot.getDirection());
        if (Math.abs(angle) > (Math.PI / 2)) {
            // 需要后退
            if (angle < 0) {
                robot.setTurnRight(Math.PI + angle);
            } else {
                robot.setTurnLeft(Math.PI - angle);
            }
            robot.setBack(100);
        } else {
            // 需要前进
            if (angle < 0) {
                robot.setTurnLeft(-1 * angle);
            } else {
                robot.setTurnRight(angle);
            }
            robot.setForward(100);
        }
    }

    // 执行冲浪躲避动作
    public void doSurfing() {
        EnemyWave surfWave = getClosestSurfableWave(); // 获取最近的波浪

        // 无波浪则返回
        if (surfWave == null) {
            return;
        }

        // 计算左右两侧的危险值
        double dangerLeft = checkDanger(surfWave, -1);
        double dangerRight = checkDanger(surfWave, 1);

        // 计算基础移动角度
        double goAngle = absoluteBearing(surfWave.fireLocation, myLocation);
        // 选择危险较小的方向
        if (dangerLeft <= dangerRight) {
            goAngle = wallSmoothing(myLocation, goAngle + (Math.PI / 2), -1); // 向左移动
        } else {
            goAngle = wallSmoothing(myLocation, goAngle - (Math.PI / 2), 1); // 向右移动
        }

        setBackAsFront(this, goAngle); // 执行移动
    }

    // 扫描到机器人时引起的事件
    @Override
    public void onScannedBot(ScannedBotEvent e) {
        myLocation = new Point2D.Double(getX(), getY());

        // 本机相对于敌方的横向速度分量
        double lateralSpeed = getSpeed() * Math.sin(e.getDirection());
        // 计算绝对角度
        double absBearing = bearingTo(e.getX(), e.getY()) + getDirection();

        // turnGunRight(normalizeRelativeAngle(getGunDirection() - absBearing));
        // fire(3);
        doFire(e);

        // 雷达锁定敌人
        // normalizeRelativeAngle()：将角度归一化为[-180， 180]范围内的相对角度 （弧度）
        // *2的设计是为了加速雷达的旋转，确保雷达能更快地覆盖到敌人位置
        setTurnRadarRight(normalizeRelativeAngle(getRadarDirection() - absBearing) * 2);

        // 记录移动方向，1表示右，-1表示左
        surfDirections.add(0, lateralSpeed >= 0 ? 1 : -1);
        // 记录绝对方位角(加上π弧度)
        surfAbsBearings.add(0, absBearing + Math.PI);

        // 通过能量差检测敌方是否开火
        double bulletPower = oppEnergy - e.getEnergy();
        if (bulletPower < 3.01 && bulletPower > 0.09 && surfDirections.size() > 2) {
            EnemyWave ew = new EnemyWave();
            // 开火时间（上一Tick）
            ew.fireTime = getTurnNumber() - 1;
            // 子弹的速度
            ew.bulletVelocity = bulletVelocity(bulletPower);
            // 已飞行距离（只过了一个Tick）
            ew.distanceTraveled = ew.bulletVelocity;
            // 子弹的方向
            ew.direction = (Integer) surfDirections.get(1);
            // 子弹的绝对角度
            ew.directAngle = (Double) surfAbsBearings.get(1);
            // 敌方开火位置（上一Tick）
            ew.fireLocation = (Point2D.Double) enemyLocation.clone();

            // 添加到波浪集合
            enemyWaves.add(ew);
        }

        // 更新敌方能量
        oppEnergy = e.getEnergy();

        //  更新敌方位置
        // enemyLocation = project(
        //         myLocation,
        //         absBearing,
        //         distanceTo(e.getX(), e.getY())
        // );
        enemyLocation = new Point2D.Double(e.getX(), e.getY());

        // 更新所有波浪的状态
        updateWaves();
        // 冲浪躲避
        doSurfing();

        rescan();
    }

    // 紧急躲避
    public void emergencyDodge(double bulletDirection) {
        double goAngle = normalizeRelativeAngle(bulletDirection + Math.PI / 2 - getDirection());

        int direction = Math.random() > 0.5 ? 1 : -1;
        // 增加角度随机扰动
        goAngle = normalizeRelativeAngle(goAngle + direction * Math.PI / 4);

        // 应用墙壁平滑
        myLocation = new Point2D.Double(getX(), getY());
        goAngle = wallSmoothing(myLocation, goAngle, direction);

        setBackAsFront(this, goAngle);
    }

    // 被敌方子弹击中时
    // @Override
    // public void onHitByBullet(HitByBulletEvent e) {
    //     // forward(100);
    //     // 紧急躲避
    //     emergencyDodge(e.getBullet().getDirection());
    //
    //     rescan();
    // }
    public void onHitByBullet(HitByBulletEvent e) {
        forward(100);
        // 如果_enemyWaves为空，说明我们可能错过了检测这个波浪
        if (!enemyWaves.isEmpty()) {
            Point2D.Double hitBulletLocation = new Point2D.Double(
                    e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

            // 遍历所有波浪，找出可能命中我们的那个
            for (int x = 0; x < enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave) enemyWaves.get(x);

                // 通过距离和速度匹配判断
                if (Math.abs(ew.distanceTraveled - myLocation.distance(ew.fireLocation)) < 50
                        && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

            if (hitWave != null) {
                // 记录命中
                logHit(hitWave, hitBulletLocation);

                // 命中后可以移除这个波浪
                enemyWaves.remove(enemyWaves.lastIndexOf(hitWave));
            }
        }
    }

    // 根据被击中的波浪和位置更新危险统计
    public void logHit(EnemyWave ew, Point2D.Double targetLocation) {
        int index = getFactorIndex(ew, targetLocation);

        // 对命中区域进行高斯式扩散标记
        for (int x = 0; x < BINS; x++) {
            // 命中点bin加1，相邻bin加1/2，再远加1/5，以此类推
            surfStats[x] += 1.0 / (Math.pow(index - x, 2) + 1);
        }
    }

    public double calculateOptimalPower(ScannedBotEvent e) {
        double distance = distanceTo(e.getX(), e.getY());
        if (getEnergy() <= 15 || distance > 1200) {
            return 0;
        }

        double enemyVelocity = e.getSpeed();
        double basePower = Math.min(3, 800 / distance);

        if (e.getEnergy() > getEnergy()) {
            basePower = 1;
        } else if (getEnergy() > 40 && (enemyVelocity < 0.5 || oppEnergy < 20)) {
            basePower = Math.min(3, basePower * 1.2);
        }

        return Math.max(0.1, basePower);
    }

    // 根据敌方距离和状态实现动态开火
    public void doFire(ScannedBotEvent e) {
        // 炮塔瞄准敌方
        double absBearing = bearingTo(e.getX(), e.getY()) + getDirection();
        double gunTurnAngle = normalizeRelativeAngle(getGunDirection() - absBearing);

        setTurnGunRight(gunTurnAngle);

        // 动态能量计算
        double power = calculateOptimalPower(e);

        if (Math.abs(gunTurnAngle) < 0.1) {
            fire(power);
        }
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        setBack(100);
        setTurnRight(Math.PI * 0.7);

        rescan();
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        setForward(100);

        rescan();
    }

    /*
     * 新增“墙壁”设定相关处理
     */

    class Wall {
        int id;
        Line2D.Double line;
        Point2D.Double center;
        double rotation;

        public Wall(ScannedWallEvent e) {
            id = e.getScannedBotId();
            rotation = e.getRotation();
            center = new Point2D.Double(e.getX(), e.getY());

            double centerX = e.getX(), centerY = e.getY(), height = e.getHeight();
            double x1 = centerX + Math.cos(rotation) * height / 2;
            double y1 = centerY + Math.sin(rotation) * height / 2;
            double x2 = centerX - Math.cos(rotation) * height / 2;
            double y2 = centerY - Math.sin(rotation) * height / 2;
            line = new Line2D.Double(x1, y1, x2, y2);
        }
    }

    public Map<Integer, Wall> detectedWalls = new HashMap<>();

    public void onScannedWall(ScannedWallEvent e) {
        int wallId = e.getScannedBotId();
        Wall w = null;
        if (!detectedWalls.containsKey(wallId)) {
            w = new Wall(e);
            detectedWalls.put(wallId, w);
        } else {
            w = detectedWalls.get(wallId);
        }

        if (getSpeed() == 0 || getDirection() >= w.rotation && getDirection() <= w.rotation + 180) {
            return;
        }

        // 避障检测
        avoidNearestWall();
    }

    public void avoidNearestWall() {
        myLocation.setLocation(getX(), getY());

        Wall nearestWall = null;
        double minDistance = Double.MAX_VALUE;
        for (Wall w : detectedWalls.values()) {
            double A = w.line.getY2() - w.line.getY1();
            double B = w.line.getX1() - w.line.getY1();
            double C = w.line.getX2() * w.line.getY1() - w.line.getX1() * w.line.getY2();

            double numerator = Math.abs(A * getX() + B * getY() + C);
            double denominator = Math.sqrt(A * A + B * B);
            double distance = numerator / denominator;
            if (distance < minDistance) {
                minDistance = distance;
                nearestWall = w;
            }
        }

        if (nearestWall != null && minDistance < 160) {
            // 避障策略
            adjustMovementAroundWall(nearestWall);
        }
    }

    public void adjustMovementAroundWall(Wall nearestWall) {
        myLocation.setLocation(getX(), getY());

        // 平行于墙壁移动，为了避免移动过程中撞到其他自定义墙，雷达跟随车身，如何避免撞到边界呢？
        double goAngle = nearestWall.rotation;
        goAngle = wallSmoothing(myLocation, goAngle, Math.random() > 0.5 ? 1 : -1);

        // setAdjustRadarForGunTurn(false);
        // setAdjustGunForBodyTurn(false);
        setBackAsFront(this, goAngle);
        // setAdjustGunForBodyTurn(true);
        // setAdjustRadarForGunTurn(true);
    }

    // 绘制方法（用于调试）
    public void onPaint(java.awt.Graphics2D g) {
        g.setColor(java.awt.Color.red);
        for (int i = 0; i < enemyWaves.size(); i++) {
            EnemyWave w = (EnemyWave) (enemyWaves.get(i));
            Point2D.Double center = w.fireLocation;

            // 计算波浪半径
            int radius = (int) w.distanceTraveled;

            // 只在波浪接近本机时绘制(半径-40 < 距离中心)
            if (radius - 40 < center.distance(myLocation))
                g.drawOval((int) (center.x - radius), (int) (center.y - radius), radius * 2, radius * 2);
        }
    }

    public static void main(String[] args) {
        new Mushroom().start();

        System.out.println("hhhh");
    }
}
