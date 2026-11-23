-- Ejecutar SOLO en una base local de pruebas que tenga schema.sql importado.
-- Crea datos ficticios dentro de una transacción y los revierte al finalizar.
SET NAMES utf8mb4;
DELIMITER ;;
CREATE PROCEDURE test_conteos_heredia()
BEGIN
  DECLARE v_paquete BIGINT UNSIGNED;
  DECLARE v_saco BIGINT UNSIGNED;
  DECLARE v_distrito BIGINT UNSIGNED;
  DECLARE v_ubicacion BIGINT UNSIGNED;
  DECLARE v_fecha DATETIME;
  DECLARE v_cambio DATETIME;
  DECLARE v_count INT;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;
  START TRANSACTION;
  INSERT INTO distritos(nombre) VALUES ('Distrito TEST');
  SET v_distrito = LAST_INSERT_ID();
  INSERT INTO ubicacion(tipo,codigo) VALUES ('MUEBLE','M-TEST');
  SET v_ubicacion = LAST_INSERT_ID();
  INSERT INTO sacos(marchamo) VALUES ('TEST001');
  SET v_saco = LAST_INSERT_ID();
  INSERT INTO paquetes(tracking_code,saco_id,distrito_id,ubicacion_id,estado)
  VALUES ('CRTEST0001',v_saco,v_distrito,v_ubicacion,'ENTREGADO_A_TRANSPORTISTA_LOCAL');
  SET v_paquete = LAST_INSERT_ID();

  CALL sp_aplicar_status_externo('CRTEST0001','Prueba de entrega','2026-10-01 18:00:00','TEST');
  SELECT delivered_at,last_state_change_at INTO v_fecha,v_cambio FROM paquetes WHERE id=v_paquete;
  CALL sp_aplicar_status_externo('CRTEST0001','Prueba de entrega','2026-10-02 18:00:00','TEST');
  IF NOT EXISTS (SELECT 1 FROM paquetes WHERE id=v_paquete AND delivered_at=v_fecha AND last_state_change_at=v_cambio) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entrega repetida cambio la fecha';
  END IF;
  SELECT COUNT(*) INTO v_count FROM paquete_estado_historial WHERE paquete_id=v_paquete AND estado_to='PRUEBA_DE_ENTREGA';
  IF v_count <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entrega duplicada en historial'; END IF;

  -- Estos movimientos deben conservar el mensajero para cumplir el CHECK del esquema.
  CALL sp_set_marchamo_distrito('CRTEST0001','TEST001','Distrito TEST',NULL,'TEST');
  CALL sp_set_marchamo_ubicacion('CRTEST0001','TEST001','M-TEST',NULL,'TEST');

  CALL sp_aplicar_status_externo('CRTEST0001','No entregable','2026-10-03 18:00:00','TEST');
  SELECT returned_at,last_state_change_at INTO v_fecha,v_cambio FROM paquetes WHERE id=v_paquete;
  CALL sp_aplicar_status_externo('CRTEST0001','No entregable','2026-10-04 18:00:00','TEST');
  IF NOT EXISTS (SELECT 1 FROM paquetes WHERE id=v_paquete AND returned_at=v_fecha AND last_state_change_at=v_cambio AND delivered_at IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Devolucion repetida cambio fecha o conservo entrega';
  END IF;
  CALL sp_aplicar_status_externo('CRTEST0001','TR A CA','2026-10-05 18:00:00','TEST');
  SELECT last_state_change_at INTO v_cambio FROM paquetes WHERE id=v_paquete;
  CALL sp_aplicar_status_externo('CRTEST0001','TR A CA','2026-10-06 18:00:00','TEST');
  IF NOT EXISTS (SELECT 1 FROM paquetes WHERE id=v_paquete AND last_state_change_at=v_cambio) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TR A CA repetido cambio fecha';
  END IF;
  ROLLBACK;
  SELECT 'OK: entrega, devolucion, TR A CA y cambios administrativos' AS resultado;
END ;;
CALL test_conteos_heredia() ;;
DROP PROCEDURE test_conteos_heredia ;;
DELIMITER ;
