"""
数据库访问层 - 基于 SQLAlchemy + PyMySQL
直接从 tourism_db 读取景点/城市数据，替代 Java MyBatis-Plus 查询
"""
from __future__ import annotations

import os
from contextlib import contextmanager
from typing import Generator

from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, declarative_base
from pydantic_settings import BaseSettings

Base = declarative_base()


class Settings(BaseSettings):
    # AI 大模型配置
    ai_api_key: str = ""
    ai_api_url: str = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    ai_model: str = "glm-4-flash"

    # 数据库配置
    db_host: str = "localhost"
    db_port: int = 3306
    db_user: str = "root"
    db_password: str = "123456"
    db_name: str = "tourism_db"

    # 服务配置
    agent_host: str = "0.0.0.0"
    agent_port: int = 8001

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}

    @property
    def database_url(self) -> str:
        return (
            f"mysql+pymysql://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )


settings = Settings()
_engine = None
_Session = None


def get_settings() -> Settings:
    """返回全局 settings 单例"""
    return settings


def get_engine():
    global _engine
    if _engine is None:
        _engine = create_engine(
            settings.database_url,
            pool_size=5,
            max_overflow=10,
            pool_pre_ping=True,
            echo=False,
        )
    return _engine


def get_session():
    global _Session
    if _Session is None:
        _Session = sessionmaker(bind=get_engine(), expire_on_commit=False)
    return _Session()


@contextmanager
def db_session() -> Generator[sessionmaker, None, None]:
    session = get_session()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


# ─────────────────────────────────────────────────────────────────────────────
# RAG 数据检索：将数据库中的真实数据注入到提示词中（替代 Java 的 buildSystemPrompt）
# ─────────────────────────────────────────────────────────────────────────────

def fetch_rag_context() -> str:
    """
    从 MySQL 获取景点统计数据，拼接成 RAG 上下文。
    对应 Java AiServiceImpl.buildSystemPrompt() 中 "系统数据库" 部分。
    """
    with db_session() as session:
        # 1. 按星级统计
        star_query = text("""
            SELECT star_level, COUNT(*) as cnt
            FROM scenic_spot
            WHERE star_level IS NOT NULL
            GROUP BY star_level
            ORDER BY star_level DESC
        """)
        star_rows = session.execute(star_query).fetchall()
        star_table = "| 星级 | 数量 | 说明 |\n| --- | --- | --- |\n"
        for row in star_rows:
            star, cnt = row
            level = "高等级景区" if star >= 4 else ("中等级景区" if star >= 3 else "普通景区")
            star_table += f"| {star}★ | {cnt} | {level} |\n"

        # 2. 热门景点 TOP30（按销量排序，含简介）
        hot_query = text("""
            SELECT spot_name, spot_type, star_level, score, ticket_price,
                   sales_volume, spot_intro
            FROM scenic_spot
            ORDER BY sales_volume DESC
            LIMIT 30
        """)
        hot_rows = session.execute(hot_query).fetchall()
        hot_table = "| # | 景点名称 | 类型 | 星级 | 评分 | 门票(元) | 销量 | 简介 |\n"
        hot_table += "| --- | --- | --- | --- | --- | --- | --- | --- |\n"
        for i, row in enumerate(hot_rows, 1):
            name, spot_type, star, score, price, sales, intro = row
            intro_str = (intro[:35] + "...") if intro and len(intro) > 35 else (intro or "")
            star_str = f"{star}★" if star else "-"
            score_str = str(score) if score is not None else "-"
            price_str = str(price) if price is not None else "-"
            sales_str = str(sales) if sales is not None else "-"
            hot_table += (
                f"| {i} | {name or '-'} | {spot_type or '-'} | "
                f"{star_str} | {score_str} | "
                f"{price_str} | {sales_str} | {intro_str} |\n"
            )

        # 3. 各城市景点数 TOP15
        city_query = text("""
            SELECT c.province, c.city, COUNT(s.id) as cnt
            FROM scenic_spot s
            JOIN scenic_city c ON s.city_id = c.id
            GROUP BY s.city_id
            ORDER BY cnt DESC
            LIMIT 15
        """)
        city_rows = session.execute(city_query).fetchall()
        city_table = "| 省份 | 城市 | 景点数 |\n| --- | --- | --- |\n"
        for row in city_rows:
            province, city, cnt = row
            city_table += f"| {province or '-'} | {city or '-'} | {cnt} |\n"

        # 4. 免费景点 TOP15
        free_query = text("""
            SELECT spot_name, spot_type, star_level, score, address
            FROM scenic_spot
            WHERE ticket_price = 0 OR ticket_price IS NULL
            LIMIT 15
        """)
        free_rows = session.execute(free_query).fetchall()
        free_table = "| 景点名称 | 类型 | 星级 | 评分 | 地址 |\n| --- | --- | --- | --- | --- |\n"
        for row in free_rows:
            name, spot_type, star, score, addr = row
            addr_str = (addr[:22] + "…") if addr and len(addr) > 22 else (addr or "")
            star_str = f"{star}★" if star else "-"
            score_str = str(score) if score is not None else "-"
            addr_str = (addr[:22] + "…") if addr and len(addr) > 22 else (addr or "")
            free_table += (
                f"| {name or '-'} | {spot_type or '-'} | "
                f"{star_str} | {score_str} | {addr_str} |\n"
            )

        # 5. 最高评分 TOP10
        score_query = text("""
            SELECT spot_name, score, star_level, spot_type, ticket_price
            FROM scenic_spot
            WHERE score IS NOT NULL
            ORDER BY score DESC
            LIMIT 10
        """)
        score_rows = session.execute(score_query).fetchall()
        score_table = "| 排名 | 景点名称 | 评分 | 星级 | 类型 | 门票(元) |\n| --- | --- | --- | --- | --- | --- |\n"
        for i, row in enumerate(score_rows, 1):
            name, sc, star, spot_type, price = row
            star_str = f"{star}★" if star else "-"
            score_table += (
                f"| {i} | {name or '-'} | {sc} | "
                f"{star_str} | {spot_type or '-'} | {price or '-'} |\n"
            )

    # 拼接完整 RAG 上下文
    rag = f"""## 📊 系统数据库全景（共678个景点，225个城市）

### 景点星级分布
{star_table}

### 🔥 热门景点 TOP30
{hot_table}

### 🏙️ 各城市景点数量 TOP15
{city_table}

### 🎁 免费景点（共{len(free_rows)}个展示）
{free_table}

### ⭐ 评分最高景点 TOP10
{score_table}

**以上为系统数据库全部可查询数据的摘要。请严格基于上述数据回答用户问题。**
"""
    return rag


def fetch_relevant_spots(keyword: str, limit: int = 10) -> list[dict]:
    """
    根据关键词模糊检索景点（轻量 RAG：按需检索，而非全量注入）
    对应原始 Java 代码的 TOP 全量注入策略的优化版本
    """
    with db_session() as session:
        query = text("""
            SELECT s.spot_name, s.spot_type, s.star_level, s.score,
                   s.ticket_price, s.sales_volume, s.spot_intro,
                   s.address, c.city, c.province
            FROM scenic_spot s
            JOIN scenic_city c ON s.city_id = c.id
            WHERE s.spot_name LIKE :kw OR s.spot_intro LIKE :kw
               OR c.city LIKE :kw
            LIMIT :limit
        """)
        rows = session.execute(query, {"kw": f"%{keyword}%", "limit": limit}).fetchall()
        return [
            {
                "name": r.spot_name, "type": r.spot_type,
                "star": r.star_level, "score": r.score,
                "price": r.ticket_price, "sales": r.sales_volume,
                "intro": r.spot_intro, "address": r.address,
                "city": r.city, "province": r.province,
            }
            for r in rows
        ]
