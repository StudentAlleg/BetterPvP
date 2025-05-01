CREATE TABLE IF NOT EXISTS SimpleAchievements
(
    Client varchar(36),
    Identifier varchar(255),
    Progress double,
    CONSTRAINT PK_SimpleAchievements PRIMARY KEY (Client, Identifier)
);

CREATE INDEX Index_clientidentifiersimpleachievements
ON SimpleAchievements(Client, Identifier);