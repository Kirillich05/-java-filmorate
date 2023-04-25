package ru.yandex.practicum.filmorate.storage.film.dao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.filmorate.exception.NotFoundException;
import ru.yandex.practicum.filmorate.exception.ValidationException;
import ru.yandex.practicum.filmorate.model.Film;
import ru.yandex.practicum.filmorate.storage.film.FilmStorage;
import ru.yandex.practicum.filmorate.storage.genre.dao.GenreDbStorage;
import ru.yandex.practicum.filmorate.storage.like.LikeStorage;
import ru.yandex.practicum.filmorate.storage.mpa.dao.MpaDbStorage;
import ru.yandex.practicum.filmorate.storage.user.UserStorage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Primary
@Slf4j
@RequiredArgsConstructor
@Component
public class FilmDbStorage implements FilmStorage {
    private final JdbcTemplate jdbcTemplate;
    private final UserStorage userStorage;
    private final MpaDbStorage mpaDbStorage;
    private final LikeStorage likeDbStorage;
    private final GenreDbStorage genreDbStorage;


    @Override
    public Film getFilmById(Integer filmId) {
        String sqlQuery =
                "SELECT film_id, name, description, release_date, duration, rating_mpa_id " +
                        "FROM films " +
                        "WHERE film_id=?";

        try {
            return jdbcTemplate.queryForObject(sqlQuery, this::mapRowToFilm, filmId);
        } catch (RuntimeException e) {
            throw new NotFoundException("Фильм не найден.");
        }
    }

    @Override
    public List<Film> findAllFilms() {
        List<Film> films = new ArrayList<>();

        SqlRowSet filmRows = jdbcTemplate.queryForRowSet(
                "SELECT film_id, name, description, release_date, duration, rating_mpa_id " +
                        "FROM films");

        while (filmRows.next()) {
            Film film = Film.builder()
                    .id(filmRows.getInt("film_id"))
                    .name(filmRows.getString("name"))
                    .description(filmRows.getString("description"))
                    .releaseDate(Objects.requireNonNull(filmRows.getDate("release_date")).toLocalDate())
                    .duration(filmRows.getInt("duration"))
                    .mpa(mpaDbStorage.getMpaById(filmRows.getInt("rating_mpa_id")))
                    .build();
            film.setGenres(genreDbStorage.getGenreForCurrentFilm(film.getId()));
            film.setLikes(likeDbStorage.getLikesForCurrentFilm(film.getId()));

            films.add(film);
        }
        return films;
    }

    @Override
    public Film addFilm(Film film) {
        SimpleJdbcInsert simpleJdbcInsert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("films")
                .usingGeneratedKeyColumns("film_id");

        film.setId(simpleJdbcInsert.executeAndReturnKey(toMap(film)).intValue());
        mpaDbStorage.addMpaToFilm(film);
        genreDbStorage.addGenreNameToFilm(film);
        genreDbStorage.addGenresForCurrentFilm(film);
        log.info("Поступил запрос на добавление фильма. Фильм добавлен.");

        return film;
    }

    @Override
    public Film updateFilm(Film film) {
        String sqlQuery =
                "UPDATE films " +
                        "SET name=?, description=?, release_date=?, duration=?, rating_mpa_id=? " +
                        "WHERE film_id=?";

        int rowsCount = jdbcTemplate.update(sqlQuery,
                film.getName(),
                film.getDescription(),
                film.getReleaseDate(),
                film.getDuration(),
                film.getMpa().getId(),
                film.getId()
        );

        mpaDbStorage.addMpaToFilm(film);
        genreDbStorage.updateGenresForCurrentFilm(film);
        genreDbStorage.addGenreNameToFilm(film);
        film.setGenres(genreDbStorage.getGenreForCurrentFilm(film.getId()));

        if (rowsCount > 0) {
            return film;
        }
        throw new NotFoundException("Фильм не найден.");
    }


    @Override
    public Film like(Integer filmId, Integer userId) {
        Film film = getFilmById(filmId);
        String sqlQuery =
                "INSERT " +
                        "INTO likes (film_id, user_id) " +
                        "VALUES(?, ?)";

        jdbcTemplate.update(sqlQuery, filmId, userId);

        return film;
    }

    @Override
    public Film deleteLike(Integer filmId, Integer userId) {
        if (userStorage.getUserById(userId) == null) {
            throw new NotFoundException("Пользователь не найден.");
        }

        Film film = getFilmById(filmId);
        String sqlQuery =
                "DELETE " +
                        "FROM likes " +
                        "WHERE film_id = ? AND user_id = ?";

        jdbcTemplate.update(sqlQuery, filmId, userId);

        return film;
    }

    @Override
    public List<Film> getRating(int count) {
        String sqlQuery =
                "SELECT films.*, COUNT(l.film_id) as count " +
                        "FROM films\n" +
                        "LEFT JOIN likes l ON films.film_id=l.film_id\n" +
                        "GROUP BY films.film_id\n" +
                        "ORDER BY count DESC\n" +
                        "LIMIT ?";

        return jdbcTemplate.query(sqlQuery, this::mapRowToFilm, count);
    }

    @Override
    public List<Film> getBestFilmsOfGenreAndYear(int count, int genre, int year) {
        String sql = "WHERE G.GENRE_ID = ? AND EXTRACT(YEAR FROM CAST(FILMS.RELEASE_DATE AS DATE)) = ? ";
            String sqlQuery =
                    "SELECT films.*, COUNT(l.film_id) as count, G.*, GT.* " +
                            "FROM films " +
                            "LEFT JOIN likes l ON films.film_id=l.film_id " +
                            "LEFT JOIN GENRE G on FILMS.FILM_ID = G.FILM_ID " +
                            "LEFT JOIN GENRE_TYPE GT on G.ID = GT.GENRE_ID " +
                            sql +
                            "GROUP BY films.film_id, gt.NAME " +
                            "ORDER BY count DESC " +
                            "LIMIT ?";

        if (year == -1 && genre == -1) {
            return getRating(count);
        } else if (genre == -1 && year  > 0) {
            sql = "WHERE EXTRACT(YEAR FROM CAST(FILMS.RELEASE_DATE AS DATE)) = ? ";
            return jdbcTemplate.query(sqlQuery, this::mapRowToFilm, year, count);
        } else if (genre > 0 && year == -1) {
            sql = "WHERE G.GENRE_ID = ? ";
            return jdbcTemplate.query(sqlQuery, this::mapRowToFilm, genre, count);
        } else {
            return jdbcTemplate.query(sqlQuery, this::mapRowToFilm, genre, year, count);
        }
    }

    private Film mapRowToFilm(ResultSet resultSet, int rowNum) throws SQLException {
        Film film = Film.builder()
                .id(resultSet.getInt("film_id"))
                .name(resultSet.getString("name"))
                .description(resultSet.getString("description"))
                .releaseDate(resultSet.getDate("release_date").toLocalDate())
                .duration(resultSet.getInt("duration"))
                .mpa(mpaDbStorage.getMpaById(resultSet.getInt("rating_mpa_id")))
                .build();
        film.setLikes(likeDbStorage.getLikesForCurrentFilm(film.getId()));
        film.setGenres(genreDbStorage.getGenreForCurrentFilm(film.getId()));

        return film;
    }

    private Map<String, Object> toMap(Film film) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", film.getName());
        values.put("description", film.getDescription());
        values.put("release_date", film.getReleaseDate());
        values.put("duration", film.getDuration());
        values.put("rating_mpa_id", film.getMpa().getId());
        return values;
    }
}
